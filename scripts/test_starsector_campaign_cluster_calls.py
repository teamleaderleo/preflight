import unittest

import starsector_campaign_cluster_calls as module


class CampaignClusterCallsTest(unittest.TestCase):
    def test_timer_groups_preserve_telemetry_hierarchy(self):
        health = {
            "campaignEngineTimes": {
                "phases": [
                    {"name": "economy", "slowestCalls": []},
                    {"name": "locations", "slowestCalls": []},
                ],
            },
        }
        self.assertEqual([
            "campaignEngineTimes.phases.economy",
            "campaignEngineTimes.phases.locations",
        ], [name for name, _calls in module.timer_groups(health)])

    def test_overlap_rankings_use_exact_intersection_and_derive_old_starts(self):
        health = {
            "campaignEngineTimes": {
                "phases": [{
                    "name": "locations",
                    "slowestCalls": [
                        {
                            "durationMillis": 80.0,
                            "startEpochMillis": 1_020.0,
                            "endEpochMillis": 1_100.0,
                        },
                        {
                            "durationMillis": 50.0,
                            "endEpochMillis": 2_050.0,
                        },
                    ],
                }],
            },
        }
        clusters = [
            ("first", 1.0, 1.05),
            ("second", 2.0, 2.1),
        ]
        rankings = module.overlap_rankings(health, clusters)
        self.assertEqual(1, len(rankings))
        self.assertEqual(2, rankings[0]["overlappingCalls"])
        self.assertAlmostEqual(80.0, rankings[0]["overlapMillis"])
        self.assertEqual(80.0, rankings[0]["maximumOverlappingCallMillis"])

    def test_nested_rows_remain_separate(self):
        call = {
            "durationMillis": 100.0,
            "startEpochMillis": 1_000.0,
            "endEpochMillis": 1_100.0,
        }
        health = {
            "outer": {"name": "engine", "slowestCalls": [call]},
            "inner": {"name": "economy", "slowestCalls": [call]},
        }
        rankings = module.overlap_rankings(health, [("cluster", 1.0, 1.1)])
        self.assertEqual(2, len(rankings))
        self.assertEqual([100.0, 100.0], [row["overlapMillis"] for row in rankings])

    def test_timer_groups_can_search_combined_runtime_documents(self):
        documents = {
            "runtimeFrameReport": {
                "campaignEngineTimes": {
                    "phases": [{"name": "economy", "slowestCalls": []}],
                },
            },
            "runtimeAdapterHealth": {
                "runtimeSemanticState": {
                    "seams": [{"name": "continue", "slowestCalls": []}],
                },
            },
        }
        self.assertEqual([
            "runtimeFrameReport.campaignEngineTimes.phases.economy",
            "runtimeAdapterHealth.runtimeSemanticState.seams.continue",
        ], [name for name, _calls in module.timer_groups(documents)])

    def test_cluster_windows_are_clipped_to_selected_steps(self):
        clusters = [("cluster", 10.0, 20.0)]
        steps = [("settled", 15.0, 25.0), ("later", 30.0, 40.0)]
        self.assertEqual(
            [("cluster inside step settled", 15.0, 20.0)],
            module.intersect_cluster_windows(clusters, steps))


if __name__ == "__main__":
    unittest.main()
