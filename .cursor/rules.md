# Cursor Agent Execution Rules (MANDATORY)

## Core Rules
- All tasks MUST modify real source code and write changes to disk.
- At least one `.kt` file MUST be changed per task.
- A Git diff MUST be produced (file state = Modified).
- Every task MUST add or modify at least one Logcat-visible log line.

## Forbidden
- Analysis-only responses
- Dry-run, simulation, or suggestion-only outputs
- Pseudo-code without real file changes

## Verification
- Each execution MUST leave a verifiable trace in code
  (e.g. Log.d / Log.e with a unique tag)

If these rules cannot be satisfied, the agent MUST explicitly say so and stop.
