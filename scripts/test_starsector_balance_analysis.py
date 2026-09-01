import importlib.util
import sys
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("starsector_balance_analysis.py")
SPEC = importlib.util.spec_from_file_location("starsector_balance_analysis", SCRIPT)
balance = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
sys.modules[SPEC.name] = balance
SPEC.loader.exec_module(balance)


class StarsectorBalanceAnalysisTest(unittest.TestCase):
    def test_json_dialect_removes_comments_suffixes_and_trailing_commas(self):
        parsed = balance.loads_starsector_json('''{
          # comment
          unquoted: 0.25f,
          "url": "https://example.invalid/#kept",
          "values": [1, 2,], // comment
        }''')
        self.assertEqual(0.25, parsed["unquoted"])
        self.assertEqual("https://example.invalid/#kept", parsed["url"])
        self.assertEqual([1, 2], parsed["values"])

    def test_bare_enum_repair_does_not_rewrite_words_after_colons_in_strings(self):
        parsed = balance.loads_starsector_json('''{
          author: "Community: Dark.Revenant, LazyWizard",
          mode: SMOOTH,
          hints: [NEVER_RENDER_IN_CAMPAIGN],
          numbers: [.8, 0., 00],
        }''')
        self.assertEqual("Community: Dark.Revenant, LazyWizard", parsed["author"])
        self.assertEqual("SMOOTH", parsed["mode"])
        self.assertEqual(["NEVER_RENDER_IN_CAMPAIGN"], parsed["hints"])
        self.assertEqual([0.8, 0.0, 0], parsed["numbers"])

    def test_bare_array_value_with_spaces_is_treated_as_one_string(self):
        parsed = balance.loads_starsector_json(
            '{removeWeaponSlots:["WS 004", WS 003], mode:SMOOTH}')
        self.assertEqual(["WS 004", "WS 003"], parsed["removeWeaponSlots"])
        self.assertEqual("SMOOTH", parsed["mode"])

    def test_pareto_requires_same_peer_and_one_strict_improvement(self):
        base = {
            "hullSize": "CRUISER", "role": "combat", "shieldType": "OMNI",
            "deploymentPoints": 20, "ordnancePoints": 100, "hitpoints": 1000,
            "armor": 500, "maxFlux": 5000, "fluxDissipation": 500, "speed": 80,
            "acceleration": 50, "turnRate": 30, "fighterBays": 0,
            "slotCapacity": 10, "shieldFluxEhp": 5000,
        }
        upgrade = dict(base, id="upgrade", speed=90)
        downgrade = dict(base, id="downgrade")
        different_role = dict(base, id="carrier", role="carrier", speed=70)
        self.assertTrue(balance.dominates(upgrade, downgrade))
        self.assertFalse(balance.dominates(downgrade, upgrade))
        self.assertFalse(balance.dominates(upgrade, different_role))

    def test_comment_markers_inside_strings_survive(self):
        value = balance.strip_starsector_comments(
            '{"hash":"#value","slash":"//value","block":"/*value*/"} #gone')
        self.assertIn('"#value"', value)
        self.assertIn('"//value"', value)
        self.assertIn('"/*value*/"', value)
        self.assertNotIn("#gone", value)

    def test_slot_compatibility_accounts_for_size_and_composite_types(self):
        medium_composite = {"size": "MEDIUM", "type": "COMPOSITE"}
        self.assertTrue(balance.slot_accepts(
            medium_composite, {"size": "SMALL", "type": "BALLISTIC"}))
        self.assertTrue(balance.slot_accepts(
            medium_composite, {"size": "MEDIUM", "type": "MISSILE"}))
        self.assertFalse(balance.slot_accepts(
            medium_composite, {"size": "MEDIUM", "type": "ENERGY"}))
        self.assertFalse(balance.slot_accepts(
            medium_composite, {"size": "LARGE", "type": "BALLISTIC"}))
        self.assertTrue(balance.slot_accepts(
            {"size": "LARGE", "type": "BUILT_IN"},
            {"size": "LARGE", "type": "ENERGY"}))

    def test_logical_path_overlay_recurses_objects_and_replaces_arrays(self):
        merged = balance.deep_merge(
            {"size": "MEDIUM", "effect": {"class": "old", "color": "blue"},
             "offsets": [1, 2]},
            {"effect": {"class": "new"}, "offsets": [3]})
        self.assertEqual("MEDIUM", merged["size"])
        self.assertEqual({"class": "new", "color": "blue"}, merged["effect"])
        self.assertEqual([3], merged["offsets"])

    def test_limited_ammo_and_pd_are_not_full_sustained_antiship_dps(self):
        harpoon = balance.weapon_dps_proxies({
            "damage/shot": "750", "chargedown": "1", "ammo": "3",
        })
        self.assertEqual(750, harpoon["burstDpsProxy"])
        self.assertEqual(37.5, harpoon["sustainedDpsProxy"])
        self.assertEqual(37.5, harpoon["antiShipDpsProxy"])

        vulcan = balance.weapon_dps_proxies({
            "damage/shot": "25", "chargedown": "0.05", "hints": "PD",
        })
        self.assertEqual(500, vulcan["sustainedDpsProxy"])
        self.assertEqual(125, vulcan["antiShipDpsProxy"])
        self.assertEqual(500, vulcan["pdDpsProxy"])

    def test_skin_materialization_preserves_base_and_applies_special_package(self):
        hulls = {"brawler": {
            "id": "brawler", "name": "Brawler", "ordnance points": "50",
            "base value": "10000", "providerId": "core", "providerName": "Core",
        }}
        specs = {"brawler": {
            "hullId": "brawler", "hullName": "Brawler", "hullSize": "FRIGATE",
            "builtInMods": ["base_mod"],
            "weaponSlots": [{"id": "WS 001", "size": "SMALL", "type": "BALLISTIC"}],
        }}
        skins = {"brawler_pather": {
            "baseHullId": "brawler", "skinHullId": "brawler_pather",
            "hullName": "Brawler (LP)", "systemId": "ammofeed",
            "builtInMods": ["safetyoverrides"], "weaponSlotChanges": {
                "WS 001": {"type": "HYBRID"}},
            "providerId": "core", "providerName": "Core", "providerOrder": 0,
        }}
        rendered_hulls, rendered_specs, quality = balance.apply_hull_skins(
            hulls, specs, skins)
        self.assertEqual(1, quality["materialized"])
        self.assertEqual("Brawler (LP)", rendered_hulls["brawler_pather"]["name"])
        self.assertEqual("ammofeed", rendered_hulls["brawler_pather"]["system id"])
        self.assertEqual(["base_mod", "safetyoverrides"],
                         rendered_specs["brawler_pather"]["builtInMods"])
        self.assertEqual("HYBRID", rendered_specs["brawler_pather"]["weaponSlots"][0]["type"])

    def test_system_source_signals_and_capability_groups_remain_evidence_based(self):
        signals, constants = balance.java_system_signals('''
          public static final float ROF_BONUS = 1f;
          // public static final float ROF_BONUS = 9f;
          stats.getBallisticRoFMult().modifyMult(id, 2f);
          stats.getBallisticWeaponFluxCostMod().modifyMult(id, 0.5f);
        ''')
        self.assertEqual(["BallisticRoFMult", "BallisticWeaponFluxCostMod"], signals)
        self.assertEqual({"ROF_BONUS": 1.0}, constants)
        groups = balance.system_capability_groups(
            {"id": "ammofeed", "name": "Accelerated Ammo Feeder", "tags": "offensive"},
            {"type": "STAT_MOD", "aiType": "WEAPON_BOOST"}, signals)
        self.assertEqual(["offense"], groups)


if __name__ == "__main__":
    unittest.main()
