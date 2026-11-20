# Cobbie 0.4.0 Defect Checklist

## Priority 1 — Ollama/LLM Integration
- [ ] LlmClient passes null to header() when apiKey empty (may throw)
- [ ] HttpClient connectTimeout hardcoded to 5s, ignores configured connectTimeout
- [ ] OllamaDiscovery.discover() called synchronously during mod init (blocks server)
- [ ] Model name hardcoded, no exact-match verification against installed models
- [ ] No structured success/failure — returns null on failure
- [ ] No bounded retry/cancellation/dedup
- [ ] Failed responses saved as conversation turns
- [ ] API keys could be logged

## Priority 2 — Chat System
- [ ] Reads ALL chat with no activation mode filter
- [ ] No owner-aware routing
- [ ] No per-player throttling
- [ ] `chat` action broadcasts to all players — unsolicited
- [ ] No max concurrent LLM requests
- [ ] No duplicate request prevention
- [ ] No player disconnect handling

## Priority 3 — AI Planning & Action Pipeline
- [ ] System prompt restricts to exactly ONE action (conflicts with multi-step)
- [ ] No task state machine
- [ ] No action feedback loop (results not fed back to LLM)
- [ ] No max action-plan length
- [ ] No execution timeouts

## Priority 4 — Owner System
- [ ] `setOwner()` has no caller
- [ ] `/spawncobbie` doesn't call `setOwner()`
- [ ] FollowMobGoal follows nearest player, not owner specifically
- [ ] Owner UUID not persisted via NBT
- [ ] No following state persistence

## Priority 5 — Inventory
- [ ] No item pickup from world (ItemEntity detection)
- [ ] No tool equipping/durability tracking
- [ ] Uses localized `getHoverName()` instead of registry IDs
- [ ] No inventory persistence via NBT
- [ ] No owner inventory inspection command/GUI

## Priority 6 — Mining
- [ ] `popResource` + `destroyBlock(true)` = double drops (duplication)
- [ ] No tool durability checks
- [ ] Direct `destroyBlock` not reach-based
- [ ] No harvest level checks

## Priority 7 — Building
- [ ] `setBlock` result ignored
- [ ] `consumeItem` before validation
- [ ] Uses localized names not registry IDs

## Priority 8 — Version
- [ ] Bump mod_version to 0.4.0
- [ ] Remove old cobbie-0.3.0.jar from mods folder
