package dev.starsector.preflight.agent;

import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Opt-in call-site attribution inside exact reviewed mod startup classes. */
final class StartupCallBreakdownPlan {
    private static final String RUNTIME =
            "dev/starsector/preflight/agent/StartupPhaseRuntime";
    private static final String CODEX_MODULES_DESCRIPTOR =
            "(Lcom/fs/starfarer/api/combat/ShipVariantAPI;Z"
                    + "Lcom/fs/starfarer/api/impl/codex/CodexEntryPlugin;"
                    + "Lcom/fs/starfarer/api/impl/codex/CodexEntryPlugin;)Ljava/util/List;";

    private static final List<Probe> PROBES = List.of(
            new Probe(
                    "com/fs/starfarer/api/impl/codex/CodexDataV2",
                    "0a2fbd188fa2937ec279d4ee41dea9ffa75f833ec7622a357857d70696f42896",
                    List.of(
                            callsIn("init", "()V",
                                    "com/fs/starfarer/api/impl/codex/CodexDataV2",
                                    List.of("createShipsCategory", "createStationsCategory",
                                            "createFightersCategory", "createWeaponsCategory",
                                            "createHullModsCategory", "createShipSystemsCategory",
                                            "createSpecialItemsCategory", "createIndustriesCategory",
                                            "createStarsAndPlanetsCategory",
                                            "createPlanetaryConditionsCategory",
                                            "createCommoditiesCategory", "createGalleryCategory",
                                            "createSkillsCategory", "createAbilitiesCategory",
                                            "createGameMechanicsCategory"),
                                    "codex.createCategories"),
                            call("init", "()V",
                                    "com/fs/starfarer/api/impl/codex/CodexDataV2",
                                    "populateShipsAndStations", null, "codex.shipsAndStations"),
                            call("init", "()V",
                                    "com/fs/starfarer/api/impl/codex/CodexDataV2",
                                    "populateShipSystems", null, "codex.shipSystems"),
                            call("init", "()V",
                                    "com/fs/starfarer/api/impl/codex/CodexDataV2",
                                    "populateHullMods", null, "codex.hullMods"),
                            call("init", "()V",
                                    "com/fs/starfarer/api/impl/codex/CodexDataV2",
                                    "populateWeapons", null, "codex.weapons"),
                            call("init", "()V",
                                    "com/fs/starfarer/api/impl/codex/CodexDataV2",
                                    "populateFighters", null, "codex.fighters"),
                            call("init", "()V",
                                    "com/fs/starfarer/api/impl/codex/CodexDataV2",
                                    "populateStarsAndPlanets", null, "codex.starsAndPlanets"),
                            call("init", "()V",
                                    "com/fs/starfarer/api/impl/codex/CodexDataV2",
                                    "populatePlanetaryConditions", null,
                                    "codex.planetaryConditions"),
                            call("init", "()V",
                                    "com/fs/starfarer/api/impl/codex/CodexDataV2",
                                    "populateSpecialItems", null, "codex.specialItems"),
                            call("init", "()V",
                                    "com/fs/starfarer/api/impl/codex/CodexDataV2",
                                    "populateCommodities", null, "codex.commodities"),
                            call("init", "()V",
                                    "com/fs/starfarer/api/impl/codex/CodexDataV2",
                                    "populateIndustries", null, "codex.industries"),
                            call("init", "()V",
                                    "com/fs/starfarer/api/impl/codex/CodexDataV2",
                                    "populateSkills", null, "codex.skills"),
                            call("init", "()V",
                                    "com/fs/starfarer/api/impl/codex/CodexDataV2",
                                    "populateAbilities", null, "codex.abilities"),
                            call("init", "()V",
                                    "com/fs/starfarer/api/impl/codex/CodexDataV2",
                                    "populateGallery", null, "codex.gallery"),
                            call("init", "()V",
                                    "com/fs/starfarer/api/impl/codex/CodexDataV2",
                                    "linkRelatedEntries", "()V", "codex.linkRelated"),
                            call("init", "()V",
                                    "com/fs/starfarer/api/impl/codex/CodexTextEntryLoader",
                                    "loadTextEntries", "()V", "codex.textEntries"),
                            call("init", "()V",
                                    "com/fs/starfarer/api/impl/codex/CodexTextEntryLoader",
                                    "linkRelated", "()V", "codex.textLinks"),
                            callsIn("init", "()V",
                                    "com/fs/starfarer/api/impl/codex/CodexDataV2",
                                    List.of("rebuildIdToEntryMap", "setCatSort",
                                            "sortSkillsCategory", "addUnknownEntry"),
                                    "codex.indexAndSort"),
                            callsIn("init", "()V", "com/fs/starfarer/api/ModPlugin",
                                    List.of("onAboutToStartGeneratingCodex",
                                            "onAboutToLinkCodexEntries",
                                            "onCodexDataGenerated"),
                                    "codex.modCallbacks"),
                            callsIn("linkRelatedEntries", "()V",
                                    "com/fs/starfarer/api/SettingsAPI",
                                    List.of("getIndustryDemand", "getIndustrySupply"),
                                    "codex.industryDemandSupply"),
                            call("populateShipsAndStations", null,
                                    "com/fs/starfarer/api/SettingsAPI",
                                    "getAllShipHullSpecs", null, "codex.ships.allHulls"),
                            call("populateShipsAndStations", null,
                                    "com/fs/starfarer/api/SettingsAPI",
                                    "getVariant", null, "codex.ships.variantLookup"),
                            call("populateShipsAndStations", null,
                                    "com/fs/starfarer/api/impl/codex/CodexDataV2",
                                    "addModulesForVariant", null, "codex.ships.modules"),
                            call("populateShipsAndStations", null,
                                    "com/fs/starfarer/api/SettingsAPI",
                                    "createFleetMember", null, "codex.ships.fleetMember"),
                            callsIn("addModulesForVariant", CODEX_MODULES_DESCRIPTOR,
                                    "com/fs/starfarer/api/combat/ShipVariantAPI",
                                    List.of("getStationModules", "getModuleVariant"),
                                    "codex.modules.traversal"),
                            call("addModulesForVariant", null,
                                    "com/fs/starfarer/api/SettingsAPI",
                                    "createFleetMember", null, "codex.modules.fleetMember"),
                            call("addModulesForVariant", null,
                                    "com/fs/starfarer/api/impl/codex/CodexDataV2",
                                    "addModuleEntry", null, "codex.modules.entry"),
                            callsIn("addModulesForVariant", CODEX_MODULES_DESCRIPTOR,
                                    "com/fs/starfarer/api/impl/codex/CodexDataV2",
                                    List.of("makeRelated", "linkFleetMemberEntryToRelated"),
                                    "codex.modules.links"),
                            call("addModulesForVariant", null,
                                    "com/fs/starfarer/api/combat/ShipVariantAPI",
                                    "toJSONObject", null, "codex.modules.identityJson"),
                            call("addModulesForVariant", null,
                                    "org/json/JSONObject", "toString", null,
                                    "codex.modules.identityJson"),
                            call("linkRelatedEntries", "()V",
                                    "com/fs/starfarer/api/impl/codex/CodexDataV2",
                                    "getBaseHullIdEvenIfNotRestorableTo", null,
                                    "codex.links.baseHullGroups"),
                            call("linkRelatedEntries", "()V",
                                    "com/fs/starfarer/api/util/ListMap", "add", null,
                                    "codex.links.groupAdds"),
                            call("linkRelatedEntries", "()V",
                                    "com/fs/starfarer/api/impl/codex/CodexDataV2",
                                    "makeRelated", null, "codex.links.makeRelated"),
                            call("linkRelatedEntries", "()V",
                                    "com/fs/starfarer/api/impl/codex/CodexEntryPlugin",
                                    "addRelatedEntry", null, "codex.links.entryAdds"),
                            call("linkRelatedEntries", "()V",
                                    "com/fs/starfarer/api/SettingsAPI", "getVariant", null,
                                    "codex.links.variantLookup"),
                            callsIn("linkRelatedEntries", "()V",
                                    "com/fs/starfarer/api/combat/ShipVariantAPI",
                                    List.of("getHullMods", "getFittedWeaponSlots", "getWings",
                                            "getWeaponId", "getHullSpec"),
                                    "codex.links.variantRelations"),
                            call("linkRelatedEntries", "()V",
                                    "com/fs/starfarer/api/SettingsAPI", "getIndustrySpec", null,
                                    "codex.links.industrySpecs"),
                            callsIn("linkRelatedEntries", "()V",
                                    "com/fs/starfarer/api/impl/campaign/econ/impl/InstallableItemEffect",
                                    List.of("getRequirements",
                                            "getConditionsRelatedToRequirements"),
                                    "codex.links.itemRequirements"))),
            new Probe(
                    CampaignEngineTimePlan.TARGET_CLASS,
                    CampaignEngineTimePlan.ORIGINAL_SHA256,
                    List.of(
                            call("getInstance",
                                    "()Lcom/fs/starfarer/campaign/CampaignEngine;",
                                    CampaignEngineTimePlan.TARGET_CLASS, "setInstance",
                                    "(Lcom/fs/starfarer/campaign/CampaignEngine;)V",
                                    "campaignEngine.publish"),
                            call("setInstance",
                                    "(Lcom/fs/starfarer/campaign/CampaignEngine;)V",
                                    "com/fs/starfarer/api/Global", "setSector",
                                    "(Lcom/fs/starfarer/api/campaign/SectorAPI;)V",
                                    "campaignEngine.setSector"),
                            call("setInstance",
                                    "(Lcom/fs/starfarer/campaign/CampaignEngine;)V",
                                    "com/fs/starfarer/api/Global", "setFactory",
                                    "(Lcom/fs/starfarer/api/FactoryAPI;)V",
                                    "campaignEngine.setFactory"),
                            call("setInstance",
                                    "(Lcom/fs/starfarer/campaign/CampaignEngine;)V",
                                    "com/fs/starfarer/combat/CombatEngine", "destroyInstance",
                                    "()V", "campaignEngine.destroyCombat"),
                            call("setInstance",
                                    "(Lcom/fs/starfarer/campaign/CampaignEngine;)V",
                                    "com/fs/starfarer/combat/CombatEngine", "getInstance",
                                    "()Lcom/fs/starfarer/combat/CombatEngine;",
                                    "campaignEngine.combatEngine"))),
            new Probe(
                    "ashlib/data/plugins/repositories/ShipRenderInfoRepo",
                    "5955d8f27dba81580e2648bbc0a7a16a9924bcd1734baf7937ab1d3417e6507f",
                    List.of(
                            call("populateRenderInfoRepo", "()V",
                                    "ashlib/data/plugins/repositories/ShipRenderInfoRepo",
                                    "populateShip",
                                    "(Lcom/fs/starfarer/api/combat/ShipHullSpecAPI;)V",
                                    "ash.populateShip"),
                            call("populateShip", "(Lcom/fs/starfarer/api/combat/ShipHullSpecAPI;)V",
                                    "ashlib/data/plugins/misc/AshMisc", "getVaraint",
                                    "(Lcom/fs/starfarer/api/combat/ShipHullSpecAPI;)Ljava/lang/String;",
                                    "ash.variantLookup"),
                            call("populateShip", "(Lcom/fs/starfarer/api/combat/ShipHullSpecAPI;)V",
                                    "ashlib/data/plugins/models/ShipRenderInfo",
                                    "getModuleSlotsFromVariantFile", "(Ljava/lang/String;)V",
                                    "ash.moduleSlotsFromVariant"),
                            call("populateShip", "(Lcom/fs/starfarer/api/combat/ShipHullSpecAPI;)V",
                                    "ashlib/data/plugins/models/ShipRenderInfo",
                                    "populateSlotShipHullsMap", "()V", "ash.slotShipHulls"),
                            call("populateShip", "(Lcom/fs/starfarer/api/combat/ShipHullSpecAPI;)V",
                                    "ashlib/data/plugins/models/ShipRenderInfo",
                                    "populateModuleList", "(Ljava/lang/String;)V",
                                    "ash.moduleList"))),
            new Probe(
                    "ashlib/data/plugins/models/ShipRenderInfo",
                    "bb8d74bfb775f63ba79aa802c7e67158b5eea80c2d3057f9fd40350fd99e1aed",
                    List.of(
                            anyCall("com/fs/starfarer/api/SettingsAPI", "loadJSON",
                                    "ash.renderInfo.loadJSON"))),
            new Probe(
                    "org/magiclib/paintjobs/MagicPaintjobManager",
                    "841f945d675920e0fad9ccf13c7fa3144b6437489b6ba11e121a3267e6b8993c",
                    List.of(
                            anyCall("com/fs/starfarer/api/SettingsAPI", "loadCSV",
                                    "magic.paintjobs.loadCSV"),
                            anyCall("com/fs/starfarer/api/SettingsAPI", "loadJSON",
                                    "magic.paintjobs.optionalJSON"),
                            anyCall("com/fs/starfarer/api/SettingsAPI", "getHullSpec",
                                    "magic.paintjobs.hullValidation"),
                            call("loadPaintjobs", "()Lkotlin/Pair;",
                                    "org/magiclib/paintjobs/MagicPaintjobManager",
                                    "loadWeaponPaintjobs",
                                    "(Lcom/fs/starfarer/api/ModSpecAPI;)Ljava/util/List;",
                                    "magic.paintjobs.weaponRows"),
                            callsIn("loadPaintjobs", "()Lkotlin/Pair;", "org/json/JSONObject",
                                    List.of("getString", "optString", "getJSONObject",
                                            "optBoolean"),
                                    "magic.paintjobs.jsonAccess"),
                            callsIn("loadPaintjobs", "()Lkotlin/Pair;",
                                    "org/lazywizard/lazylib/ext/json/JSONExtensionsKt",
                                    List.of("optFloat"), "magic.paintjobs.floatAccess"),
                            callsIn("loadPaintjobs", "()Lkotlin/Pair;",
                                    "com/fs/starfarer/api/util/Misc", List.of("optColor"),
                                    "magic.paintjobs.colorAccess"),
                            callsIn("loadPaintjobs", "()Lkotlin/Pair;", "kotlin/text/StringsKt",
                                    List.of("trim", "isBlank", "split$default"),
                                    "magic.paintjobs.stringOps"),
                            call("loadPaintjobs", "()Lkotlin/Pair;",
                                    "org/magiclib/util/MagicTxt", "ellipsizeStringAfterLength",
                                    null, "magic.paintjobs.ellipsize"),
                            anyCall("org/apache/log4j/Logger", "info",
                                    "magic.paintjobs.infoLog"))),
            new Probe(
                    "org/magiclib/Magic_modPlugin",
                    "0ef80d14aa00142bf5dd4d8eb8448cc5dbd342d47d4b48fbe9e57c6b22c00000",
                    List.of(
                            topCall("data/scripts/Magic_modPlugin", "onApplicationLoad",
                                    "magic.legacyPlugin"),
                            topCall("org/magiclib/util/MagicSettings", "loadModSettings",
                                    "magic.settings"),
                            topCall("org/magiclib/bounty/MagicBountyLoader",
                                    "loadBountiesFromJSON", "magic.devBounties"),
                            topCall("org/magiclib/util/MagicInterference", "loadInterference",
                                    "magic.interference"),
                            topCall("org/magiclib/plugins/MagicAutoTrails", "getTrailData",
                                    "magic.autoTrails"),
                            topCall("org/magiclib/util/MagicVariables", "loadThemesBlacklist",
                                    "magic.themesBlacklist"),
                            topCall("org/magiclib/util/MagicSettings", "getBoolean",
                                    "magic.booleanSetting"),
                            topCall("org/magiclib/achievements/MagicAchievementManager",
                                    "getInstance", "magic.achievementInstance"),
                            topCall("org/magiclib/achievements/MagicAchievementManager",
                                    "onApplicationLoad", "magic.achievementLoad"),
                            topCall("org/magiclib/paintjobs/MagicPaintjobManager",
                                    "onApplicationLoad", "magic.paintjobs"),
                            topCall("org/magiclib/subsystems/MagicSubsystemsManager", "initialize",
                                    "magic.subsystems"))),
            new Probe(
                    "exerelin/utilities/NexConfig",
                    "a59894d38876b8ed92d3d11726d776743b1203177ccacc8a6f8d79f7fd71ff7c",
                    List.of(
                            call("loadSettings", "()V",
                                    "com/fs/starfarer/api/SettingsAPI",
                                    "getMergedJSONForMod",
                                    "(Ljava/lang/String;Ljava/lang/String;)Lorg/json/JSONObject;",
                                    "nex.config.mergedJSON"),
                            call("loadSettings", "()V",
                                    "exerelin/utilities/NexConfig", "loadModFactionList", "()V",
                                    "nex.config.modFactionList"))),
            new Probe(
                    "exerelin/utilities/NexFactionConfig",
                    "f6aa5a769744f82498c05c0a878ddd36bfa4b32e15136bf08e2dd996447c9c6f",
                    List.of(
                            call("<init>", "(Ljava/lang/String;)V",
                                    "exerelin/utilities/StringHelper", "getString",
                                    "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
                                    "nex.factionConfig.defaults"),
                            call("<init>", "(Ljava/lang/String;)V",
                                    "exerelin/utilities/NexFactionConfig", "loadFactionConfig",
                                    "()V", "nex.factionConfig.load"),
                            call("loadFactionConfig", "()V",
                                    "com/fs/starfarer/api/SettingsAPI",
                                    "getMergedJSONForMod",
                                    "(Ljava/lang/String;Ljava/lang/String;)Lorg/json/JSONObject;",
                                    "nex.factionConfig.mergedJSON"),
                            callsIn("loadFactionConfig", "()V", "org/json/JSONObject",
                                    List.of("optBoolean", "optDouble", "optString", "optInt",
                                            "has", "getJSONArray", "getJSONObject", "getString",
                                            "getDouble", "keys", "sortedKeys"),
                                    "nex.factionConfig.jsonAccess"),
                            call("loadFactionConfig", "()V",
                                    "exerelin/utilities/NexFactionConfig", "fillRelationshipMap",
                                    "(Lorg/json/JSONObject;Ljava/util/Map;Ljava/lang/String;)V",
                                    "nex.factionConfig.relationships"),
                            call("loadFactionConfig", "()V",
                                    "exerelin/utilities/NexFactionConfig", "loadDispositions",
                                    "(Lorg/json/JSONObject;)V",
                                    "nex.factionConfig.dispositions"),
                            call("loadFactionConfig", "()V",
                                    "exerelin/utilities/NexFactionConfig", "loadCustomStations",
                                    "(Lorg/json/JSONObject;)V",
                                    "nex.factionConfig.customStations"),
                            call("loadFactionConfig", "()V",
                                    "exerelin/utilities/NexFactionConfig", "loadDefenceStations",
                                    "(Lorg/json/JSONObject;)V",
                                    "nex.factionConfig.defenceStations"),
                            call("loadFactionConfig", "()V",
                                    "exerelin/utilities/NexFactionConfig", "loadVengeanceNames",
                                    "(Lorg/json/JSONObject;)V",
                                    "nex.factionConfig.vengeanceNames"),
                            call("loadFactionConfig", "()V",
                                    "exerelin/utilities/NexFactionConfig", "loadStartSpecialItems",
                                    "(Lorg/json/JSONObject;)V",
                                    "nex.factionConfig.startSpecialItems"),
                            call("loadFactionConfig", "()V",
                                    "exerelin/utilities/NexFactionConfig", "loadStartShips",
                                    "(Lorg/json/JSONObject;)V",
                                    "nex.factionConfig.startShips"),
                            callsIn("loadFactionConfig", "()V", "exerelin/utilities/NexUtils",
                                    List.of("JSONArrayToStringArray", "JSONArrayToArrayList",
                                            "jsonToMap"),
                                    "nex.factionConfig.conversions"),
                            call("loadFactionConfig", "()V",
                                    "exerelin/utilities/StringHelper", "flattenToAscii",
                                    "(Ljava/lang/String;)Ljava/lang/String;",
                                    "nex.factionConfig.flattenNames"),
                            call("getStartShipTypeIfAvailable",
                                    "(Lorg/json/JSONObject;Ljava/lang/String;"
                                            + "Lexerelin/utilities/NexFactionConfig$StartFleetType;)V",
                                    "exerelin/utilities/NexFactionConfig",
                                    "isStartingFleetValid", "(Ljava/util/List;)Z",
                                    "nex.startShips.validateFleet"),
                            call("isStartingFleetValid", "(Ljava/util/List;)Z",
                                    "com/fs/starfarer/api/SettingsAPI", "doesVariantExist",
                                    "(Ljava/lang/String;)Z", "nex.startShips.variantExists"),
                            call("getStartShipTypeIfAvailable",
                                    "(Lorg/json/JSONObject;Ljava/lang/String;"
                                            + "Lexerelin/utilities/NexFactionConfig$StartFleetType;)V",
                                    "exerelin/utilities/NexUtils", "JSONArrayToArrayList",
                                    "(Lorg/json/JSONArray;)Ljava/util/ArrayList;",
                                    "nex.startShips.arrayConversion"))),
            new Probe(
                    "org/dark/shaders/ShaderModPlugin",
                    "5863b38d7ea73ed65fb8d214e525daed0318f4563b92a15d22e0981cec275981",
                    List.of(
                            topCall("org/dark/shaders/util/ShaderLib", "init", "gfx.shaderInit"),
                            topCall("org/dark/graphics/util/ShipColors", "init", "gfx.shipColors"),
                            topCall("org/dark/shaders/light/LightData",
                                    "readLightDataCSVNoOverwrite", "gfx.lightCsv"),
                            topCall("org/dark/shaders/util/TextureData",
                                    "readTextureDataCSVNoOverwrite", "gfx.textureCsv"),
                            topCall("org/dark/shaders/util/GraphicsLibSettings",
                                    "loadWave", "gfx.loadWave"),
                            topCall("org/dark/shaders/util/GraphicsLibSettings",
                                    "loadSmallRipple", "gfx.loadSmallRipple"),
                            topCall("org/dark/shaders/util/GraphicsLibSettings",
                                    "loadLargeRipple", "gfx.loadLargeRipple"),
                            topCall("org/dark/shaders/util/GraphicsLibSettings",
                                    "loadThreat", "gfx.loadThreat"),
                            topCall("org/dark/shaders/util/TextureData",
                                    "autoGenMissingNormalMaps", "gfx.autoGenMissingNormals"))));

    private StartupCallBreakdownPlan() {
    }

    static byte[] transform(ClassSignature signature, byte[] originalBytes) {
        Probe probe = probe(signature);
        if (probe == null) {
            return null;
        }
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(originalBytes).accept(owner, ClassReader.EXPAND_FRAMES);
        boolean changed = false;
        for (MethodNode method : owner.methods) {
            List<Match> matches = new ArrayList<>();
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (!(instruction instanceof MethodInsnNode invoked)
                        || "<init>".equals(invoked.name)) {
                    continue;
                }
                for (CallMatcher call : probe.calls()) {
                    if (call.matches(method, invoked)) {
                        matches.add(new Match(call.label(), invoked));
                        break;
                    }
                }
            }
            if (matches.isEmpty()) {
                continue;
            }
            int tokenLocal = method.maxLocals;
            method.maxLocals += 2;
            for (Match match : matches) {
                MethodInsnNode invoked = match.instruction();
                InsnList before = new InsnList();
                before.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC, RUNTIME, "hotCallStart", "()J", false));
                before.add(new VarInsnNode(Opcodes.LSTORE, tokenLocal));
                method.instructions.insertBefore(invoked, before);
                InsnList after = new InsnList();
                after.add(new LdcInsnNode(match.label()));
                after.add(new VarInsnNode(Opcodes.LLOAD, tokenLocal));
                after.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME,
                        "hotCallEnd", "(Ljava/lang/String;J)V", false));
                method.instructions.insert(invoked, after);
                changed = true;
            }
        }
        if (!changed) {
            return null;
        }
        ClassWriter writer = new SafeClassWriter(
                ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        owner.accept(writer);
        return writer.toByteArray();
    }

    static Probe probe(ClassSignature signature) {
        for (Probe probe : PROBES) {
            if (probe.className().equals(signature.internalName())
                    && probe.sha256().equals(signature.sha256())) {
                return probe;
            }
        }
        return null;
    }

    static List<Probe> probes() {
        return PROBES;
    }

    private static Call topCall(String owner, String name, String label) {
        return new Call("onApplicationLoad", "()V", owner, name, null, label);
    }

    private static Call anyCall(String owner, String name, String label) {
        return new Call(null, null, owner, name, null, label);
    }

    private static Call call(String caller, String callerDescriptor, String owner,
            String name, String descriptor, String label) {
        return new Call(caller, callerDescriptor, owner, name, descriptor, label);
    }

    private static CallGroup callsIn(String caller, String callerDescriptor, String owner,
            List<String> names, String label) {
        return new CallGroup(caller, callerDescriptor, owner, List.copyOf(names), label);
    }

    record Probe(String className, String sha256, List<? extends CallMatcher> calls) {
    }

    private interface CallMatcher {
        boolean matches(MethodNode method, MethodInsnNode invoked);

        String label();
    }

    record Call(String caller, String callerDescriptor, String owner, String name,
            String descriptor, String label) implements CallMatcher {
        public boolean matches(MethodNode method, MethodInsnNode invoked) {
            return (caller == null || caller.equals(method.name))
                    && (callerDescriptor == null || callerDescriptor.equals(method.desc))
                    && owner.equals(invoked.owner)
                    && name.equals(invoked.name)
                    && (descriptor == null || descriptor.equals(invoked.desc));
        }

    }

    record CallGroup(String caller, String callerDescriptor, String owner, List<String> names,
            String label) implements CallMatcher {
        public boolean matches(MethodNode method, MethodInsnNode invoked) {
            return caller.equals(method.name)
                    && callerDescriptor.equals(method.desc)
                    && owner.equals(invoked.owner)
                    && names.contains(invoked.name);
        }

    }

    private record Match(String label, MethodInsnNode instruction) {
    }
}
