package dev.starsector.preflight.core.bisect;

import dev.starsector.preflight.core.checkpoints.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Directed Mod Dependency Graph $G=(V, E)$ supporting transitive closure computation,
 * leaf-balanced partitioning, and circular cycle (SCC) detection.
 */
public final class ModDependencyGraph {

    public static final Set<String> KNOWN_PREREQUISITES = Set.of(
            "lw_lazylib",
            "MagicLib",
            "GraphicsLib"
    );

    private final List<String> allMods;
    private final Map<String, Set<String>> dependencies;
    private final Map<String, Set<String>> dependents;

    public ModDependencyGraph(List<String> allMods, Map<String, Set<String>> dependencies) {
        this.allMods = List.copyOf(new LinkedHashSet<>(allMods));
        this.dependencies = new LinkedHashMap<>();
        this.dependents = new LinkedHashMap<>();

        for (String mod : this.allMods) {
            this.dependencies.put(mod, new LinkedHashSet<>());
            this.dependents.put(mod, new LinkedHashSet<>());
        }

        if (dependencies != null) {
            dependencies.forEach((mod, reqs) -> {
                if (reqs != null) {
                    Set<String> modDeps = this.dependencies.computeIfAbsent(mod, k -> new LinkedHashSet<>());
                    for (String req : reqs) {
                        modDeps.add(req);
                        this.dependents.computeIfAbsent(req, k -> new LinkedHashSet<>()).add(mod);
                    }
                }
            });
        }
    }

    public List<String> allMods() {
        return allMods;
    }

    public Map<String, Set<String>> dependencies() {
        Map<String, Set<String>> copy = new LinkedHashMap<>();
        dependencies.forEach((k, v) -> copy.put(k, Collections.unmodifiableSet(new LinkedHashSet<>(v))));
        return Collections.unmodifiableMap(copy);
    }

    public Set<String> getDependencies(String modId) {
        return Collections.unmodifiableSet(dependencies.getOrDefault(modId, Set.of()));
    }

    public Set<String> getDependents(String modId) {
        return Collections.unmodifiableSet(dependents.getOrDefault(modId, Set.of()));
    }

    /**
     * Computes the transitive dependency closure $\text{Closure}(T)$ for a given set of root mods.
     * Guaranteed to terminate without infinite loops even in the presence of cyclic dependencies.
     */
    public Set<String> transitiveClosure(Set<String> roots) {
        Set<String> closure = new LinkedHashSet<>(roots);
        Deque<String> queue = new ArrayDeque<>(roots);

        while (!queue.isEmpty()) {
            String current = queue.poll();
            Set<String> deps = dependencies.getOrDefault(current, Set.of());
            for (String dep : deps) {
                if (closure.add(dep)) {
                    queue.add(dep);
                }
            }
        }
        return new LinkedHashSet<>(closure);
    }

    /**
     * Extracts leaf nodes from a given subset in the dependency graph.
     * A leaf is a mod that NO other mod in the subset depends upon.
     */
    public List<String> getLeaves(Set<String> subset) {
        Set<String> requiredByOthers = new HashSet<>();
        for (String mod : subset) {
            requiredByOthers.addAll(dependencies.getOrDefault(mod, Set.of()));
        }
        List<String> leaves = new ArrayList<>();
        for (String mod : subset) {
            if (!requiredByOthers.contains(mod)) {
                leaves.add(mod);
            }
        }
        return leaves.isEmpty() ? new ArrayList<>(subset) : leaves;
    }

    /**
     * Computes Strongly Connected Components (SCC) using Tarjan's algorithm to detect cycles.
     */
    public List<Set<String>> findStronglyConnectedComponents() {
        List<Set<String>> sccs = new ArrayList<>();
        Map<String, Integer> indices = new HashMap<>();
        Map<String, Integer> lowLinks = new HashMap<>();
        Deque<String> stack = new ArrayDeque<>();
        Set<String> onStack = new HashSet<>();
        int[] index = new int[]{0};

        for (String node : allMods) {
            if (!indices.containsKey(node)) {
                strongConnect(node, indices, lowLinks, stack, onStack, index, sccs);
            }
        }
        return sccs;
    }

    private void strongConnect(
            String node,
            Map<String, Integer> indices,
            Map<String, Integer> lowLinks,
            Deque<String> stack,
            Set<String> onStack,
            int[] index,
            List<Set<String>> sccs
    ) {
        indices.put(node, index[0]);
        lowLinks.put(node, index[0]);
        index[0]++;
        stack.push(node);
        onStack.add(node);

        for (String neighbor : dependencies.getOrDefault(node, Set.of())) {
            if (!allMods.contains(neighbor)) {
                continue;
            }
            if (!indices.containsKey(neighbor)) {
                strongConnect(neighbor, indices, lowLinks, stack, onStack, index, sccs);
                lowLinks.put(node, Math.min(lowLinks.get(node), lowLinks.get(neighbor)));
            } else if (onStack.contains(neighbor)) {
                lowLinks.put(node, Math.min(lowLinks.get(node), indices.get(neighbor)));
            }
        }

        if (lowLinks.get(node).equals(indices.get(node))) {
            Set<String> scc = new LinkedHashSet<>();
            String w;
            do {
                w = stack.pop();
                onStack.remove(w);
                scc.add(w);
            } while (!node.equals(w));
            sccs.add(scc);
        }
    }

    /**
     * Builds a ModDependencyGraph by scanning mod_info.json files under installRoot/mods/.
     */
    public static ModDependencyGraph fromInstallation(Path installRoot, List<String> activeMods) {
        if (installRoot == null) {
            return new ModDependencyGraph(activeMods, Map.of());
        }
        Path modsDir = installRoot.resolve("mods");
        if (!Files.isDirectory(modsDir)) {
            return new ModDependencyGraph(activeMods, Map.of());
        }

        Map<String, Set<String>> parsedDeps = new LinkedHashMap<>();
        Set<String> knownModIds = new LinkedHashSet<>(activeMods);

        try (Stream<Path> stream = Files.list(modsDir)) {
            stream.filter(Files::isDirectory).forEach(modFolder -> {
                Path modInfo = modFolder.resolve("mod_info.json");
                if (Files.isRegularFile(modInfo)) {
                    try {
                        String content = Files.readString(modInfo, StandardCharsets.UTF_8);
                        Map<String, Object> json = JsonParser.parseObject(content);
                        String id = json.get("id") instanceof String s ? s : null;
                        if (id != null) {
                            knownModIds.add(id);
                            Set<String> reqs = new LinkedHashSet<>();
                            extractDependencies(json.get("dependencies"), reqs);
                            extractDependencies(json.get("requiredMods"), reqs);
                            parsedDeps.put(id, reqs);
                        }
                    } catch (Exception ignored) {
                        // Resilient to malformed json
                    }
                }
            });
        } catch (IOException ignored) {
        }

        return new ModDependencyGraph(new ArrayList<>(knownModIds), parsedDeps);
    }

    private static void extractDependencies(Object depObj, Set<String> target) {
        if (depObj instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    Object depId = map.get("id");
                    if (depId instanceof String s && !s.isBlank()) {
                        target.add(s);
                    }
                } else if (item instanceof String s && !s.isBlank()) {
                    target.add(s);
                }
            }
        }
    }
}
