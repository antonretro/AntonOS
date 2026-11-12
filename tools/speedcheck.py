#!/usr/bin/env python3
"""Retro Launcher speed check: runs PLAN.md's speed budget over ADB and prints pass/fail.

Usage (PC, box on the network):
  python tools/speedcheck.py --device <box-ip>:5555 --screenshot-only
  python tools/speedcheck.py --device <box-ip>:5555 --yes
  python tools/speedcheck.py --device <box-ip>:5555 --yes --compile-test

Safety: this script presses keys and starts apps on the TV. Without --yes it only
takes a screenshot (saved next to this script as last-screen.png) so you can check
nobody is using the box first. --compile-test runs `cmd package compile -m speed -f`
on the apps it times (reversible with `cmd package compile -m speed-profile -f <pkg>`
or `cmd package compile --reset <pkg>`).

Numbers the app itself must report (logcat tag RLPerf, written by the launcher):
  RLPerf key2focus=<ms>      key event time -> first frame of focus animation
  RLPerf tab=<ms>            tab key -> new tab content drawn
  RLPerf channel=<ms>        OK on a channel -> first video frame
  RLPerf zap=<ms>            channel up/down -> first video frame
"""
import argparse, os, re, shutil, subprocess, sys, time

LAUNCHER = "com.antonretro.launcher"
HOME_ACT = LAUNCHER + "/.HomeActivity"
OTHER_APPS = {  # package -> activity used for start timing (resolved if None)
    "com.liskovsoft.smarttubetv.beta": None,
    "com.liskovsoft.smarttubetv": None,
    "com.retroarch.ra32": None,
    "com.retroarch": None,
    "org.xbmc.kodi": None,
}
DEFAULT_ADB = os.environ.get("ADB", "adb")   # set ADB=<path to adb> if it is not on PATH

BUDGET = {
    "key2focus": 50, "home": 300, "tab": 250, "channel": 2000, "zap": 1500,
    "cold": 1500, "mem_mb": 80, "jank_pct": 1.0,
}

def adb_path():
    if os.path.exists(DEFAULT_ADB):
        return DEFAULT_ADB
    return shutil.which("adb") or "adb"

class Box:
    def __init__(self, device):
        self.adb = adb_path(); self.dev = device
        if ":" in device:
            subprocess.run([self.adb, "connect", device], capture_output=True, timeout=20)
    def sh(self, cmd, timeout=60):
        r = subprocess.run([self.adb, "-s", self.dev, "shell", cmd], capture_output=True, text=True,
                           timeout=timeout, encoding="utf-8", errors="replace")
        return r.stdout
    def pull(self, src, dst):
        subprocess.run([self.adb, "-s", self.dev, "pull", src, dst], capture_output=True, timeout=60)

def installed(box):
    return set(l.split(":", 1)[1].strip() for l in box.sh("pm list packages").splitlines() if ":" in l)

def main_activity(box, pkg):
    for cat in ("android.intent.category.LEANBACK_LAUNCHER", "android.intent.category.LAUNCHER"):
        out = box.sh(f"cmd package resolve-activity --brief -a android.intent.action.MAIN -c {cat} {pkg}")
        lines = [l.strip() for l in out.splitlines() if "/" in l]
        if lines:
            return lines[-1]
    return None

def start_w(box, component, cold=True):
    pkg = component.split("/")[0]
    if cold:
        box.sh(f"am force-stop {pkg}"); time.sleep(1.5)
    out = box.sh(f"am start -W -n {component}", timeout=90)
    # ThisTime = our own activity; TotalTime also counts whatever had to start before it
    m = re.search(r"ThisTime:\s*(\d+)", out) or re.search(r"TotalTime:\s*(\d+)", out)
    return int(m.group(1)) if m else None

def pss_mb(box, pkg):
    out = box.sh(f"dumpsys meminfo {pkg}")
    m = re.search(r"TOTAL\s+(\d+)", out)
    return round(int(m.group(1)) / 1024, 1) if m else None

def gfx_jank(box, pkg, keys):
    box.sh(f"dumpsys gfxinfo {pkg} reset")
    box.sh("; ".join(f"input keyevent {k}; sleep 0.25" for k in keys), timeout=120)
    out = box.sh(f"dumpsys gfxinfo {pkg}")
    total = re.search(r"Total frames rendered:\s*(\d+)", out)
    janky = re.search(r"Janky frames:\s*(\d+)\s*\(([\d.]+)%\)", out)
    p90 = re.search(r"90th percentile:\s*(\d+)ms", out)
    return (int(total.group(1)) if total else 0, float(janky.group(2)) if janky else None,
            int(p90.group(1)) if p90 else None)

PERF_FILE = "/sdcard/Android/data/com.antonretro.launcher/files/perf.log"

def rlperf(box):
    # the box drops log lines from ordinary apps, so the launcher also writes them to a file
    out = box.sh(f"cat {PERF_FILE} 2>/dev/null; logcat -d -s RLPerf:I", timeout=30)
    vals = {}
    for k, v in re.findall(r"RLPerf\W.*?(\w+)=(\d+)", out):
        vals.setdefault(k, []).append(int(v))
    return vals

def row(name, value, budget, unit="ms", lower=True):
    if value is None:
        print(f"  {name:<34} {'n/a':>8}   budget {budget}{unit}   SKIP"); return None
    ok = value <= budget if lower else value >= budget
    print(f"  {name:<34} {value:>8}{unit:<3} budget {budget}{unit}   {'PASS' if ok else 'FAIL'}")
    return ok

def screenshot(box):
    here = os.path.dirname(os.path.abspath(__file__))
    box.sh("screencap -p /sdcard/rl-speed.png"); dst = os.path.join(here, "last-screen.png")
    box.pull("/sdcard/rl-speed.png", dst)
    focus = re.search(r"mCurrentFocus=\S+ \S+ (\S+)\}", box.sh("dumpsys window | grep mCurrentFocus"))
    return dst, focus.group(1) if focus else "?"

def compile_test(box, pkgs):
    print("\nAhead-of-time compile test (am start -W cold, ms):")
    for pkg in pkgs:
        act = main_activity(box, pkg)
        if not act:
            print(f"  {pkg}: no launch activity, skipped"); continue
        before = [start_w(box, act) for _ in range(2)]
        t0 = time.time(); box.sh(f"cmd package compile -m speed -f {pkg}", timeout=600); took = round(time.time() - t0)
        after = [start_w(box, act) for _ in range(2)]
        box.sh("input keyevent 3"); time.sleep(1)
        print(f"  {pkg:<34} before {before}  after {after}  (compile took {took}s)")

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--device", default=os.environ.get("BOX", ""))
    ap.add_argument("--yes", action="store_true", help="really press keys / start apps on the box")
    ap.add_argument("--screenshot-only", action="store_true")
    ap.add_argument("--compile-test", action="store_true")
    a = ap.parse_args()
    box = Box(a.device)
    shot, focus = screenshot(box)
    print(f"Screen saved to {shot}; focused window: {focus}")
    if a.screenshot_only or not a.yes:
        if not a.yes: print("Not pressing anything. Check the screenshot, then rerun with --yes.")
        return 0
    pk = installed(box)
    results = []
    print("\nRetro Launcher speed budget:")
    if LAUNCHER in pk:
        box.sh(f"logcat -c; rm -f {PERF_FILE}")
        results.append(row("Cold start to first frame", start_w(box, HOME_ACT), BUDGET["cold"]))
        time.sleep(3)
        results.append(row("Idle memory (PSS)", pss_mb(box, LAUNCHER), BUDGET["mem_mb"], "MB"))
        frames, jank, p90 = gfx_jank(box, LAUNCHER, [22] * 6 + [20, 20, 19, 19] + [21] * 6)
        results.append(row(f"Janky frames ({frames} frames)", jank, BUDGET["jank_pct"], "%"))
        settings = "com.android.settings/.Settings"
        box.sh(f"am start -W -n {settings}"); time.sleep(2)
        out = box.sh(f"am start -W -a android.intent.action.MAIN -c android.intent.category.HOME")
        m = re.search(r"ThisTime:\s*(\d+)", out)
        results.append(row("Home key to Home usable", int(m.group(1)) if m else None, BUDGET["home"]))
        perf = rlperf(box)
        for key, label in (("key2focus", "Key press to focus moving"), ("tab", "Tab switch"),
                           ("channel", "Channel to picture"), ("zap", "Channel up/down")):
            v = perf.get(key); results.append(row(label + (f" (worst of {len(v)})" if v else ""), max(v) if v else None, BUDGET[key]))
    else:
        print("  Retro Launcher is not installed yet; skipping launcher checks.")
    if a.compile_test:
        compile_test(box, [p for p in [LAUNCHER, *OTHER_APPS] if p in pk])
    fails = sum(1 for r in results if r is False)
    print(f"\n{fails} fail(s), {sum(1 for r in results if r)} pass, {sum(1 for r in results if r is None)} skipped")
    return 1 if fails else 0

if __name__ == "__main__":
    sys.exit(main())
