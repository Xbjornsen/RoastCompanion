"""Human-verified labels shared by train.py, train_v2.py and harness.py.
Keyed by startTimeMs (== WAV filename stamp), not sessionId (reused after reinstalls)."""

EXCLUDE_SESSIONS = {28}

# Negative sessions (keyed by startTimeMs): the roaster run EMPTY — no beans,
# no cracks, just the machine's organic sounds (fan, motor, drum, heater ticks,
# cooling). Every loud transient here is a hard negative: label them ambient so
# the model learns the machine's own noises are NOT cracks. Do NOT run these
# through label_session — their autoDetected FC is a false fire on machine noise.
NEGATIVE_SESSIONS = {1783387226188}   # sess 30: empty full run into cooling, 2026-07-07

# Human-verified-by-ear event times (ms). Keyed by startTimeMs (== the WAV
# filename stamp), NOT sessionId: sessionId restarts after an app reinstall, so
# the old batch (training_17814xx) reused 25/26/27/28/29 and keying by sessionId
# would force these labels onto the wrong roast. These override the JSON's
# tap-confirmed/auto values, which lag real onset by reaction time.
# Captured by listening to the last 3 min in clips/player.html.
GROUND_TRUTH = {
    1781835881068: {"FC_START": 622_400, "SC_START": 719_700},  # sess 2:  FC 10:22, SC 11:59
    1781836828189: {"FC_START": 608_200, "SC_START": 694_500},  # sess 3:  FC 10:08, SC 11:34
    # Sessions 10 & 11: owner verified FC start+end were accurate; no SC label
    # (beans pulled as SC starts, so no real SC roll was recorded).
    1782607843770: {"FC_START": 554_811, "FC_END": 676_772},    # sess 10: FC 9:15 -> 11:17
    1782608711997: {"FC_START": 541_693, "FC_END": 672_195},    # sess 11: FC 9:02 -> 11:12
    # Sessions 21,22,25,26: by-ear from clips/player.html (fan-filtered). Moving
    # FC to the true onset pushes the 8-min false-crack noise (auto-fired at
    # 8:00-8:11 on 25/26/27) into the ambient run-up, so it trains as a hard
    # negative automatically. FC_END omitted (not logged); the FC zone caps at
    # SC-5s. Session 25's SC verified real (survived the 1.8 kHz high-pass).
    1782891235209: {"FC_START": 546_200, "SC_START": 695_500},  # sess 21: FC 9:06, SC 11:35
    1782892267930: {"FC_START": 500_900, "SC_START": 609_500},  # sess 22: FC 8:20, SC 10:09
    1782971218277: {"FC_START": 615_100, "SC_START": 739_800},  # sess 23: FC 10:15, SC 12:19
    1782972283371: {"FC_START": 616_600, "SC_START": 710_200},  # sess 25: FC 10:16, SC 11:50
    1783130361156: {"FC_START": 573_800, "SC_START": 670_200},  # sess 26: FC 9:33, SC 11:10
    1783131432591: {"FC_START": 600_500, "SC_START": 710_600},  # sess 27: FC 10:00, SC 11:50
    # Priority 1 by-ear from clips/player.html (2026-09-23)
    1782083781012: {"FC_START": 603_600, "SC_START": 706_200},  # sess 4:  FC 10:03, SC 11:46
    1782084719085: {"FC_START": 571_200, "SC_START": 733_700},  # sess 5:  FC 9:31, SC 12:13
    1782283382681: {"FC_START": 623_200, "SC_START": 738_300},  # sess 6:  FC 10:23, SC 12:18
    1782284673101: {"FC_START": 534_800, "SC_START": 678_600},  # sess 7:  FC 8:54, SC 11:18
    1782285754176: {"FC_START": 566_000, "SC_START": 672_400},  # sess 8:  FC 9:25, SC 11:12
    1783584363852: {"FC_START": 548_700, "SC_START": 690_400},  # sess 33: FC 9:08, SC 11:30
    1783925610893: {"FC_START": 570_100, "SC_START": 737_900},  # sess 34: FC 9:30, SC 12:17
    1783926654131: {"FC_START": 571_700, "SC_START": 693_200},  # sess 35: FC 9:31, SC 11:33
}

NEGATIVE_SESSIONS = {1783387226188}   # sess 30: empty full run into cooling, 2026-07-07

# Human-verified-by-ear event times (ms). Keyed by startTimeMs (== the WAV
# filename stamp), NOT sessionId: sessionId restarts after an app reinstall, so
# the old batch (training_17814xx) reused 25/26/27/28/29 and keying by sessionId
# would force these labels onto the wrong roast. These override the JSON's
# tap-confirmed/auto values, which lag real onset by reaction time.
# Captured by listening to the last 3 min in clips/player.html.
GROUND_TRUTH = {
    1781835881068: {"FC_START": 622_400, "SC_START": 719_700},  # sess 2:  FC 10:22, SC 11:59
    1781836828189: {"FC_START": 608_200, "SC_START": 694_500},  # sess 3:  FC 10:08, SC 11:34
    # Sessions 10 & 11: owner verified FC start+end were accurate; no SC label
    # (beans pulled as SC starts, so no real SC roll was recorded).
    1782607843770: {"FC_START": 554_811, "FC_END": 676_772},    # sess 10: FC 9:15 -> 11:17
    1782608711997: {"FC_START": 541_693, "FC_END": 672_195},    # sess 11: FC 9:02 -> 11:12
    # Sessions 21,22,25,26: by-ear from clips/player.html (fan-filtered). Moving
    # FC to the true onset pushes the 8-min false-crack noise (auto-fired at
    # 8:00-8:11 on 25/26/27) into the ambient run-up, so it trains as a hard
    # negative automatically. FC_END omitted (not logged); the FC zone caps at
    # SC-5s. Session 25's SC verified real (survived the 1.8 kHz high-pass).
    1782891235209: {"FC_START": 546_200, "SC_START": 695_500},  # sess 21: FC 9:06, SC 11:35
    1782892267930: {"FC_START": 500_900, "SC_START": 609_500},  # sess 22: FC 8:20, SC 10:09
    1782971218277: {"FC_START": 615_100, "SC_START": 739_800},  # sess 23: FC 10:15, SC 12:19
    1782972283371: {"FC_START": 616_600, "SC_START": 710_200},  # sess 25: FC 10:16, SC 11:50
    1783130361156: {"FC_START": 573_800, "SC_START": 670_200},  # sess 26: FC 9:33, SC 11:10
    1783131432591: {"FC_START": 600_500, "SC_START": 710_600},  # sess 27: FC 10:00, SC 11:50
    # Priority 1 by-ear from clips/player.html (2026-09-23)
    1782083781012: {"FC_START": 603_600, "SC_START": 706_200},  # sess 4:  FC 10:03, SC 11:46
    1782084719085: {"FC_START": 571_200, "SC_START": 733_700},  # sess 5:  FC 9:31, SC 12:13
    1782283382681: {"FC_START": 623_200, "SC_START": 738_300},  # sess 6:  FC 10:23, SC 12:18
    1782284673101: {"FC_START": 534_800, "SC_START": 678_600},  # sess 7:  FC 8:54, SC 11:18
    1782285754176: {"FC_START": 566_000, "SC_START": 672_400},  # sess 8:  FC 9:25, SC 11:12
    1783584363852: {"FC_START": 548_700, "SC_START": 690_400},  # sess 33: FC 9:08, SC 11:30
    1783925610893: {"FC_START": 570_100, "SC_START": 737_900},  # sess 34: FC 9:30, SC 12:17
    1783926654131: {"FC_START": 571_700, "SC_START": 693_200},  # sess 35: FC 9:31, SC 11:33
}

GROUND_TRUTH = {
    1781835881068: {"FC_START": 622_400, "SC_START": 719_700},  # sess 2:  FC 10:22, SC 11:59
    1781836828189: {"FC_START": 608_200, "SC_START": 694_500},  # sess 3:  FC 10:08, SC 11:34
    # Sessions 10 & 11: owner verified FC start+end were accurate; no SC label
    # (beans pulled as SC starts, so no real SC roll was recorded).
    1782607843770: {"FC_START": 554_811, "FC_END": 676_772},    # sess 10: FC 9:15 -> 11:17
    1782608711997: {"FC_START": 541_693, "FC_END": 672_195},    # sess 11: FC 9:02 -> 11:12
    # Sessions 21,22,25,26: by-ear from clips/player.html (fan-filtered). Moving
    # FC to the true onset pushes the 8-min false-crack noise (auto-fired at
    # 8:00-8:11 on 25/26/27) into the ambient run-up, so it trains as a hard
    # negative automatically. FC_END omitted (not logged); the FC zone caps at
    # SC-5s. Session 25's SC verified real (survived the 1.8 kHz high-pass).
    1782891235209: {"FC_START": 546_200, "SC_START": 695_500},  # sess 21: FC 9:06, SC 11:35
    1782892267930: {"FC_START": 500_900, "SC_START": 609_500},  # sess 22: FC 8:20, SC 10:09
    1782971218277: {"FC_START": 615_100, "SC_START": 739_800},  # sess 23: FC 10:15, SC 12:19
    1782972283371: {"FC_START": 616_600, "SC_START": 710_200},  # sess 25: FC 10:16, SC 11:50
    1783130361156: {"FC_START": 573_800, "SC_START": 670_200},  # sess 26: FC 9:33, SC 11:10
    1783131432591: {"FC_START": 600_500, "SC_START": 710_600},  # sess 27: FC 10:00, SC 11:50
    # Priority 1 by-ear from clips/player.html (2026-09-23)
    1782083781012: {"FC_START": 603_600, "SC_START": 706_200},  # sess 4:  FC 10:03, SC 11:46
    1782084719085: {"FC_START": 571_200, "SC_START": 733_700},  # sess 5:  FC 9:31, SC 12:13
    1782283382681: {"FC_START": 623_200, "SC_START": 738_300},  # sess 6:  FC 10:23, SC 12:18
    1782284673101: {"FC_START": 534_800, "SC_START": 678_600},  # sess 7:  FC 8:54, SC 11:18
    1782285754176: {"FC_START": 566_000, "SC_START": 672_400},  # sess 8:  FC 9:25, SC 11:12
    1783584363852: {"FC_START": 548_700, "SC_START": 690_400},  # sess 33: FC 9:08, SC 11:30
    1783925610893: {"FC_START": 570_100, "SC_START": 737_900},  # sess 34: FC 9:30, SC 12:17
    1783926654131: {"FC_START": 571_700, "SC_START": 693_200},  # sess 35: FC 9:31, SC 11:33
}

