package com.rishi.cobbie.action;

/**
 * Single intent emitted by the LLM. Cobbie's action executor runs these on the
 * entity tick. Fields are optional per action type.
 *
 * Allowed actions:
 *   follow  - come to the named player
 *   wave    - wave animation
 *   walk-to - pathfind to x,y,z
 *   mine    - mine the block at x,y,z
 *   place   - place block named target at x,y,z
 *   attack  - attack nearest entity of type target
 *   chat    - say target text in chat
 *   wait    - do nothing this tick
 *   answer  - speak only (no world action)
 */
public record ActionIntent(
        String action,   // follow | wave | walk-to | mine | place | attack | chat | wait | answer
        String target,   // player name / block type / entity type / chat text
        int x, int y, int z,
        String data      // extra JSON payload
) {}
