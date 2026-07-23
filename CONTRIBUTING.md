# Contributing to MiriYum

This workflow applies to people and AI collaborators. It keeps scope, lifecycle, implementation evidence, and canonical documentation under distinct owners.

## Start with an Issue

Except for a clearly explained small-change exception, create or select a GitHub Issue before implementation. The Issue owns:

- the intended outcome and exact in-scope and out-of-scope paths;
- the current assignee and any delegated work;
- acceptance criteria;
- the verification plan and known risks.

Fix the exact change paths before editing. Discovery patterns may help identify paths, but the Issue or an ignored `.superpowers/sdd/` inventory must freeze the resulting file list.

## Track lifecycle once

Use the single GitHub Project `Status` field as the only lifecycle state, from backlog through completion. Do not mirror that status in repository work logs, checklists, or AI documents.

An Issue describes the work contract; it does not prove implementation or verification. Move it to the completed lifecycle state only after the acceptance criteria and completion evidence have been reviewed.

## Submit a Pull Request

The Pull Request owns the change summary and the evidence produced by implementation. Record:

- acceptance-criterion-to-change mapping;
- commands actually run and their observed results;
- tests or checks not run and why;
- risks, rollback considerations, and document impacts;
- links to CI evidence and the requested reviewer focus.

CI owns its own run output. Do not transcribe it into a permanent repository log. A configured workflow or planned check is not proof that a clean checkout passed or that a repository check is required.

## Delegation and handoff

The current task assignee retains responsibility for integration, verification, and the final completion claim. A delegate receives an explicit bounded scope, required inputs, expected outputs, and evidence format. Reconcile the returned work against the Issue and current repository state before integrating it.

If a temporary delegation artifact or path inventory is necessary, place it only under the ignored `.superpowers/sdd/` path. Do not create a durable work-log or lifecycle mirror in the repository.

## Documentation ownership

Update the canonical product, policy, architecture, feature, or quality document when behavior or a lasting decision changes. Link to the owner instead of copying its rules into the Issue, Pull Request, or AI workflow documents.
