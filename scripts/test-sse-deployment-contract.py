from __future__ import annotations

import http.client
import json
import os
from pathlib import Path
import subprocess
import time
import unittest
import uuid
from unittest.mock import MagicMock, patch


ROOT = Path(__file__).resolve().parents[1]
LOCAL_ENV = ROOT / "deploy" / "local" / ".env.example"
LOCAL_COMPOSE = ROOT / "deploy" / "local" / "docker-compose.dev.yml"
LOADTEST_COMPOSE = ROOT / "deploy" / "local" / "docker-compose.loadtest.yml"
LOCAL_NGINX = ROOT / "deploy" / "local" / "nginx.sse.conf"
CADDYFILE = ROOT / "deploy" / "local" / "Caddyfile.loadtest"
SSE_SNIPPET = ROOT / "deploy" / "nginx" / "templates" / "snippets" / "sse-location.conf"
HTTP_TEMPLATE = ROOT / "deploy" / "nginx" / "templates" / "http.conf.template"
HTTPS_TEMPLATE = ROOT / "deploy" / "nginx" / "templates" / "https.conf.template"
PROD_COMPOSE = ROOT / "deploy" / "docker-compose.prod.yml"
SSE_K6_DOCKERFILE = ROOT / "performance" / "k6" / "sse" / "Dockerfile"
K6_WORKFLOW = ROOT / ".github" / "workflows" / "k6-contract.yml"
SSE_RUNBOOK = ROOT / "docs" / "deployment" / "sse-runtime-runbook.md"


def run(*args: str, check: bool = True) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        args,
        cwd=ROOT,
        check=check,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )


def compose_json(*files: Path, profile: str | None = None) -> dict:
    command = ["docker", "compose", "--env-file", str(LOCAL_ENV)]
    for file in files:
        command.extend(("-f", str(file)))
    if profile is not None:
        command.extend(("--profile", profile))
    command.extend(("config", "--format", "json"))
    return json.loads(run(*command).stdout)


def exposed_services(config: dict, environment_name: str) -> list[str]:
    return sorted(
        name
        for name, service in config.get("services", {}).items()
        if environment_name in service.get("environment", {})
    )


class DockerSseSmoke:
    def __init__(self) -> None:
        suffix = f"{os.getpid()}-{uuid.uuid4().hex[:8]}"
        self.network = f"miriyum-sse-contract-{suffix}"
        self.backend = f"miriyum-sse-backend-{suffix}"
        self.proxy = f"miriyum-sse-proxy-{suffix}"

    def __enter__(self) -> "DockerSseSmoke":
        try:
            run("docker", "network", "create", self.network)
            fake_command = (
                "while true; do "
                "{ printf 'HTTP/1.1 200 OK\\r\\nContent-Type: text/event-stream\\r\\n"
                "Connection: close\\r\\n\\r\\nid: contract-cursor\\nevent: notifications.changed\\n"
                "data: {}\\n\\n'; sleep 5; } | nc -l -p 8080; "
                "done"
            )
            run(
                "docker", "run", "-d", "--rm",
                "--name", self.backend,
                "--network", self.network,
                "--network-alias", "backend",
                "alpine:3.21",
                "sh", "-ec", fake_command,
            )
            run(
                "docker", "run", "-d", "--rm",
                "--name", self.proxy,
                "--network", self.network,
                "-p", "127.0.0.1::8080",
                "-v", f"{LOCAL_NGINX}:/etc/nginx/conf.d/default.conf:ro",
                "-v", f"{SSE_SNIPPET}:/opt/miriyum-nginx-templates/snippets/sse-location.conf:ro",
                "nginx:1.27-alpine",
            )
            return self
        except BaseException:
            self.cleanup()
            raise

    def __exit__(self, exc_type, exc_value, traceback) -> None:
        self.cleanup()

    def cleanup(self) -> None:
        run("docker", "rm", "-f", self.proxy, check=False)
        run("docker", "rm", "-f", self.backend, check=False)
        run("docker", "network", "rm", self.network, check=False)

    def host_port(self) -> int:
        mapping = run("docker", "port", self.proxy, "8080/tcp").stdout.strip()
        return int(mapping.rsplit(":", 1)[1])

    def wait_until_ready(self) -> int:
        port = self.host_port()
        for _ in range(30):
            connection = http.client.HTTPConnection("127.0.0.1", port, timeout=0.2)
            try:
                connection.request("GET", "/")
                response = connection.getresponse()
                response.read()
                if response.status == 404:
                    return port
            except (OSError, http.client.HTTPException):
                pass
            finally:
                connection.close()
            time.sleep(0.1)
        raise AssertionError("Nginx SSE contract proxy did not become ready")


def first_sse_frame() -> tuple[bytes, float, int, str | None]:
    with DockerSseSmoke() as smoke:
        port = smoke.wait_until_ready()
        connection = http.client.HTTPConnection("127.0.0.1", port, timeout=3)
        started = time.monotonic()
        try:
            connection.request(
                "GET",
                "/api/v1/consumers/me/notification-events",
                headers={"Accept": "text/event-stream"},
            )
            response = connection.getresponse()
            lines: list[bytes] = []
            while True:
                line = response.readline()
                if line in (b"", b"\r\n", b"\n"):
                    break
                lines.append(line)
            elapsed = time.monotonic() - started
            return b"".join(lines), elapsed, response.status, response.getheader("Content-Type")
        finally:
            connection.close()


class SseDeploymentContractTest(unittest.TestCase):
    def test_default_local_stack_does_not_enable_sse(self) -> None:
        config = compose_json(LOCAL_COMPOSE)

        backend_environment = config["services"]["backend"].get("environment", {})

        self.assertNotIn("MIRIYUM_SSE_ENABLED", backend_environment)
        self.assertNotIn("sse-proxy", config["services"])

    def test_loadtest_stack_enables_only_bounded_local_sse(self) -> None:
        config = compose_json(LOCAL_COMPOSE, LOADTEST_COMPOSE, profile="loadtest")
        backend_environment = config["services"]["backend"]["environment"]

        expected = {
            "MIRIYUM_SSE_ENABLED": "true",
            "MIRIYUM_SSE_TIMEOUT": "PT30S",
            "MIRIYUM_SSE_HEARTBEAT_INTERVAL": "PT5S",
            "MIRIYUM_SSE_CORRECTION_INTERVAL": "PT2S",
            "MIRIYUM_SSE_CORRECTION_BATCH_SIZE": "100",
            "MIRIYUM_SSE_MAX_CONNECTIONS_TOTAL": "200",
            "MIRIYUM_SSE_MAX_CONNECTIONS_PER_ACCOUNT": "6",
        }
        self.assertEqual(expected, {name: str(backend_environment[name]) for name in expected})
        self.assertEqual(["backend"], exposed_services(config, "MIRIYUM_SSE_CURSOR_SECRET"))
        self.assertIn("sse-proxy", config["services"])
        self.assertEqual("grafana/k6:2.1.0", config["services"]["loadtest"]["image"])
        self.assertNotIn("K6_DEPENDENCIES_MANIFEST", config["services"]["loadtest"].get("environment", {}))
        sse_loadtest = config["services"]["sse-loadtest"]
        self.assertEqual(
            str(SSE_K6_DOCKERFILE.parent),
            sse_loadtest["build"]["context"],
        )
        self.assertEqual("Dockerfile", sse_loadtest["build"]["dockerfile"])
        self.assertNotIn("environment", sse_loadtest)
        self.assertNotIn("sysctls", sse_loadtest)
        self.assertIn("sse-slow-loadtest", config["services"])
        slow_loadtest = config["services"]["sse-slow-loadtest"]
        self.assertEqual(
            "4096 4096 4096",
            slow_loadtest["sysctls"]["net.ipv4.tcp_rmem"],
        )
        self.assertEqual(sse_loadtest["image"], slow_loadtest["image"])
        self.assertIn(
            "listen 8080 sndbuf=4k;",
            LOCAL_NGINX.read_text(encoding="utf-8"),
        )

        dockerfile = SSE_K6_DOCKERFILE.read_text(encoding="utf-8")
        self.assertIn("FROM grafana/xk6:1.4.11 AS builder", dockerfile)
        self.assertIn("xk6 build v1.2.2", dockerfile)
        self.assertIn("github.com/phymbert/xk6-sse@v0.1.12", dockerfile)
        self.assertIn("FROM grafana/k6:1.2.2", dockerfile)

    def test_loadtest_stack_accepts_explicit_backpressure_timing(self) -> None:
        with patch.dict(os.environ, {
            "MIRIYUM_LOADTEST_SSE_TIMEOUT": "PT90S",
            "MIRIYUM_LOADTEST_SSE_HEARTBEAT_INTERVAL": "PT0.001S",
        }):
            config = compose_json(LOCAL_COMPOSE, LOADTEST_COMPOSE, profile="loadtest")

        backend_environment = config["services"]["backend"]["environment"]
        self.assertEqual("PT90S", backend_environment["MIRIYUM_SSE_TIMEOUT"])
        self.assertEqual(
            "PT0.001S",
            backend_environment["MIRIYUM_SSE_HEARTBEAT_INTERVAL"],
        )
        runbook = SSE_RUNBOOK.read_text(encoding="utf-8")
        self.assertIn("try {", runbook)
        self.assertIn("} finally {", runbook)
        self.assertIn("throw 'slow-client validation failed'", runbook)
        self.assertIn("throw 'SSE loadtest timing restoration failed'", runbook)

    def test_nginx_readiness_requires_an_http_response(self) -> None:
        smoke = DockerSseSmoke()
        reset_connection = MagicMock()
        reset_connection.getresponse.side_effect = ConnectionResetError
        ready_response = MagicMock(status=404)
        ready_response.read.return_value = b""
        ready_connection = MagicMock()
        ready_connection.getresponse.return_value = ready_response

        with (
            patch.object(smoke, "host_port", return_value=18080),
            patch.object(
                http.client,
                "HTTPConnection",
                side_effect=[reset_connection, ready_connection],
            ) as connection_factory,
            patch.object(time, "sleep") as sleep,
        ):
            port = smoke.wait_until_ready()

        self.assertEqual(18080, port)
        self.assertEqual(2, connection_factory.call_count)
        reset_connection.request.assert_called_once_with("GET", "/")
        ready_connection.request.assert_called_once_with("GET", "/")
        ready_response.read.assert_called_once_with()
        sleep.assert_called_once_with(0.1)

    def test_nginx_flushes_first_sse_frame_before_upstream_close(self) -> None:
        frame, elapsed, status, content_type = first_sse_frame()

        self.assertEqual(200, status)
        self.assertEqual("text/event-stream", content_type)
        self.assertIn(b"event: notifications.changed", frame)
        self.assertIn(b"data: {}", frame)
        self.assertLess(elapsed, 2.0)

    def test_production_templates_mount_and_include_the_sse_snippet(self) -> None:
        include = "include /opt/miriyum-nginx-templates/snippets/sse-location.conf;"
        self.assertEqual(2, HTTP_TEMPLATE.read_text(encoding="utf-8").count(include))
        self.assertEqual(2, HTTPS_TEMPLATE.read_text(encoding="utf-8").count(include))

        compose = PROD_COMPOSE.read_text(encoding="utf-8")
        self.assertIn(
            "./nginx/templates:/opt/miriyum-nginx-templates:ro",
            compose,
        )

    def test_local_caddy_sse_matcher_matches_the_nginx_store_id_contract(self) -> None:
        caddy = CADDYFILE.read_text(encoding="utf-8")
        self.assertIn(
            "path_regexp sse ^/api/v1/(consumers/me/(notification-events|waiting-events)|store-operators/stores/[1-9][0-9]*/waiting-events)$",
            caddy,
        )
        self.assertNotIn("stores/*/waiting-events", caddy)

    def test_ci_builds_and_inspects_the_pinned_sse_runner(self) -> None:
        workflow = K6_WORKFLOW.read_text(encoding="utf-8")

        self.assertIn(
            "docker build --tag miriyum/k6-sse:contract performance/k6/sse",
            workflow,
        )
        self.assertIn("k6 v1.2.2", workflow)
        self.assertIn("github.com/phymbert/xk6-sse v0.1.12", workflow)
        self.assertIn("miriyum/k6-sse:contract inspect", workflow)
        self.assertIn('runtime_image="miriyum/k6-sse:contract"', workflow)


if __name__ == "__main__":
    unittest.main(verbosity=2)
