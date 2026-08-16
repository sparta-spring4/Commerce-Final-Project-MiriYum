package com.miriyum.domain.search.semantic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.HttpMethod.PUT;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.miriyum.domain.search.config.OpenAiEmbeddingProperties;
import com.miriyum.domain.search.config.QdrantSemanticSearchProperties;
import java.util.List;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class SemanticHttpAdapterTest {

    @Test
    void sendsOnlyRemainingExpressionToOpenAiEmbeddingEndpoint() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.openai.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.openai.test/v1/embeddings"))
                .andExpect(method(POST))
                .andExpect(header("Authorization", "Bearer secret"))
                .andExpect(content().json("""
                        {
                          "model":"text-embedding-3-small",
                          "input":"얼큰한 국물",
                          "encoding_format":"float",
                          "dimensions":2
                        }
                        """))
                .andRespond(withSuccess("""
                        {"data":[{"embedding":[0.1,0.2]}]}
                        """, MediaType.APPLICATION_JSON));
        var client = new OpenAiTextEmbeddingClient(builder.build(),
                new OpenAiEmbeddingProperties(
                        "https://api.openai.test", "secret", "text-embedding-3-small",
                        2, 100, 100));

        assertThat(client.embed("얼큰한 국물")).containsExactly(0.1f, 0.2f);
        server.verify();
    }

    @Test
    void readsMinimalQdrantPayloadAndKeepsMenuTextOutOfUpsertPayload() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://qdrant.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        var properties = new QdrantSemanticSearchProperties(
                "http://qdrant.test", "qdrant-secret", "menus", 0.55, 100, 100);
        var index = new QdrantSemanticMenuIndex(builder.build(), properties);
        server.expect(requestTo("http://qdrant.test/collections/menus/points/query"))
                .andExpect(method(POST))
                .andExpect(header("api-key", "qdrant-secret"))
                .andExpect(content().string(Matchers.allOf(
                        Matchers.containsString("\"with_payload\":[\"menuId\",\"storeId\",\"versionNumber\"]"),
                        Matchers.not(Matchers.containsString("얼큰한 국물")))))
                .andRespond(withSuccess("""
                        {"result":{"points":[{
                          "score":0.91,
                          "payload":{"menuId":11,"storeId":2,"versionNumber":3}
                        }]}}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://qdrant.test/collections/menus/points"))
                .andExpect(method(PUT))
                .andExpect(content().string(Matchers.allOf(
                        Matchers.containsString("\"menuId\":11"),
                        Matchers.not(Matchers.containsString("김치찌개")))))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://qdrant.test/collections/menus/points/delete"))
                .andExpect(method(POST))
                .andExpect(content().json("""
                        {"filter":{"must":[]}}
                        """))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThat(index.search(List.of(0.1f, 0.2f), 40)).containsExactly(
                new SemanticMenuHit(11L, 2L, 3, 0.91));
        index.upsert(
                new SemanticMenuDocument(11L, 2L, 3, "김치찌개 얼큰한 국물"),
                List.of(0.1f, 0.2f));
        index.clear();
        server.verify();
    }

    @Test
    void rejectsMalformedEmbeddingResponse() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.openai.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.openai.test/v1/embeddings"))
                .andRespond(withSuccess("""
                        {"data":[{"embedding":[0.1]}]}
                        """, MediaType.APPLICATION_JSON));
        var client = new OpenAiTextEmbeddingClient(builder.build(),
                new OpenAiEmbeddingProperties(
                        "https://api.openai.test", "secret", "text-embedding-3-small",
                        2, 100, 100));

        assertThatThrownBy(() -> client.embed("얼큰한 국물"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("embedding provider unavailable");
        server.verify();
    }
}
