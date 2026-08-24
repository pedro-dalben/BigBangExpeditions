package com.bigbangcraft.expeditions.lifecycle;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure evacuation planning: given who is (or was) inside the expedition
 * dimension, produce the exact action list. Server code executes the plan;
 * tests verify it without bootstrapping Minecraft.
 *
 * Policy:
 * - Players currently inside are teleported to overworld spawn.
 * - Players with a stale "inside" marker (disconnected inside) are handled at
 *   their next join by the same plan shape (EVICT).
 * - Nobody is ever sent back to a stored position inside the dimension after
 *   an eviction: the world may be about to be regenerated.
 */
public final class EvacuationPlan {

    public enum ActionType { TELEPORT_OUT, EVICT_ON_JOIN }

    public record Action(ActionType type, String playerName) {}

    private EvacuationPlan() {}

    public static List<Action> plan(List<String> playersCurrentlyInside,
                                    List<String> staleInsideMarkers) {
        List<Action> out = new ArrayList<>();
        if (playersCurrentlyInside != null) {
            for (String name : playersCurrentlyInside) {
                out.add(new Action(ActionType.TELEPORT_OUT, name));
            }
        }
        if (staleInsideMarkers != null) {
            for (String name : staleInsideMarkers) {
                if (playersCurrentlyInside != null && playersCurrentlyInside.contains(name)) continue;
                out.add(new Action(ActionType.EVICT_ON_JOIN, name));
            }
        }
        return out;
    }

    /** True when the given plan leaves nobody inside. */
    public static boolean clearsDimension(List<Action> plan) {
        return plan.stream().allMatch(a -> a.type() == ActionType.TELEPORT_OUT || a.type() == ActionType.EVICT_ON_JOIN);
    }
}
