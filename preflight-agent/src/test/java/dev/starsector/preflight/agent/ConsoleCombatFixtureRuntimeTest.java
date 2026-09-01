package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

final class ConsoleCombatFixtureRuntimeTest {
    @AfterEach
    void reset() {
        ConsoleCombatFixtureRuntime.reset();
    }

    @Test
    void recipeIsFixedBoundedAndContainsNoArbitraryConsoleInput() {
        var commands = ConsoleCombatFixtureRuntime.commands();
        assertEquals(7, commands.size());
        assertEquals(24, commands.stream()
                .filter(command -> command.type().endsWith(".AddShip"))
                .mapToInt(command -> Integer.parseInt(
                        command.arguments().substring(command.arguments().lastIndexOf(' ') + 1)))
                .sum());
        assertEquals(Set.of("AddShip", "Repair"), commands.stream()
                .map(command -> command.type().substring(command.type().lastIndexOf('.') + 1))
                .collect(java.util.stream.Collectors.toSet()));
        for (var command : commands) {
            String lowered = command.arguments().toLowerCase(java.util.Locale.ROOT);
            assertFalse(lowered.contains("runcode"));
            assertFalse(lowered.contains("alias"));
            assertFalse(lowered.contains(";"));
            assertFalse(lowered.contains("\n"));
        }
        assertEquals("Repair", commands.get(commands.size() - 1).type()
                .substring(commands.get(commands.size() - 1).type().lastIndexOf('.') + 1));
        assertTrue(commands.get(commands.size() - 2).arguments().startsWith("sunder_Assault "));
        assertEquals("console-simulation-fleet-v5", ConsoleCombatFixtureRuntime.RECIPE_ID);
    }
}
