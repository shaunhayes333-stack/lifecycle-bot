import unittest
from types import SimpleNamespace
import edge_replay_8537 as replay


class ReplayContractTest(unittest.TestCase):
    def row(self, at=1_000_000, net=100.0, **extra):
        return dict(t=at, horizonMs=900_000, mint='Abcdefghijkmnpqrstuvwxyz123456789pump',
                    lane='MOONSHOT', net=net, mcap=20_000, fill=True, **extra)

    def test_only_fillable_correct_horizon_scoped_and_plausible_rows_teach(self):
        row = self.row()
        self.assertTrue(replay.valid_label(row))
        for change in ({'fill': False}, {'horizonMs': 300_000}, {'net': -101}, {'t': None},
                       {'mint': 'So11111111111111111111111111111111111111112'}):
            self.assertFalse(replay.valid_label(dict(row, **change)))
        self.assertTrue(replay.valid_label(dict(row, mint='So11111111111111111111111111111111111111112', venueScopeEpoch=8660)))

    def test_buyer_and_demand_features_are_not_discarded(self):
        unknown = replay.features(self.row())
        zero = replay.features(self.row(newBuyers15s8666=0))
        buyers = replay.features(self.row(newBuyers15s8666=6))
        self.assertNotEqual(unknown, zero)
        self.assertNotEqual(zero, buyers)
        self.assertNotEqual(buyers, replay.features(self.row(newBuyers15s8666=6, sustainedDemand=True)))
        self.assertLess(max(replay.features(self.row(marketReclaim=True))), replay.DIM)

    def test_label_cannot_teach_a_decision_before_it_arrives(self):
        class Model:
            def __init__(self, *_): self.count = 0; self.w = None; self.last = 0.0
            def learn(self, x, y): self.count += 1; self.last = y
            def solve(self): self.w = [self.last]
            def predict(self, x): return self.last
        args = SimpleNamespace(ridge=4.0, warm=1, lane_own=600, solve_every=50)
        first = self.row(labelObservedAtMs=2_100_000)
        before = self.row(at=2_000_000, net=-20.0)
        after = self.row(at=2_100_000, net=3.0)
        out = list(replay.replay_predictions([first, before, after], args, Model))
        self.assertTrue(replay.math.isnan(out[0][1]))
        self.assertTrue(replay.math.isnan(out[1][1]))
        self.assertAlmostEqual(100.0, out[2][1])  # log target inverted back to real percent

    def test_legacy_labels_wait_through_grace(self):
        self.assertEqual(2_350_000, replay.label_available_at(self.row()))

    def test_lazy_decay_matches_eager_sufficient_statistics(self):
        model = replay.Ridge(2, 4.0)
        model.learn({0: 1.0, 1: 2.0}, 10.0)
        model.learn({0: 1.0}, -5.0)
        self.assertAlmostEqual(replay.FORGET * 4.0, model.xtx[1][1] * model.scale)
        self.assertAlmostEqual(replay.FORGET * 10.0 - 5.0, model.xty[0] * model.scale)
        model.solve()
        self.assertTrue(all(replay.math.isfinite(v) for v in model.w))


if __name__ == '__main__': unittest.main()
