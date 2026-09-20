# T3 Usage widget

A Nothing-OS-styled Android home-screen widget (2x3) showing the Claude / Codex subscription
limits that T3 Code knows about. It replaces the stock T3 Code "Subscription usage" widget
without forking or modifying the T3 Code app, so Play Store updates keep flowing.

## How the data flows

```
T3 server (nas, t3code.service)
  └─ writes ~/.t3/caches/{claudeAgent,codex}.json  (re-probed every 5 min while a T3 client is connected)
      └─ systemd --user: t3-usage-export.path / .timer → ~/.local/bin/t3-usage-export
          └─ ~/.local/share/t3-usage/usage.json
              └─ tailscale serve --https=10000 (tailnet only)
                  └─ phone: this app fetches https://<nas>.<tailnet>.ts.net:10000/usage.json
                      every 15 min (JobScheduler), on widget taps, and on launcher updates
```

The widget shows T3's `checkedAt` time in the footer ("AS OF 9:24"). A red `!` there means the
phone's last fetch failed (Tailscale down, NAS asleep...). Percentages are **% used**; the red
dots grow as you consume a window; the number turns red at 80 %. Windows whose reset time has
passed are shown as 0 % / "RESET" until T3 re-probes.

Tap the widget body → opens T3 Code's usage screen (`t3code://settings/usage?tab=limits`) and
schedules fetches (T3 itself re-probes when it comes to the foreground). Tap the bottom strip →
refresh in place. The launcher icon opens a small settings/diagnostics screen (URL, last fetch,
last error, widget sizes).

## Rendering

`UsageRenderer` draws two `ALPHA_8` masks (mono + red) which the launcher tints via
`android:tint` in `res/layout/usage_widget.xml`, so the widget follows the system light/dark
theme without re-rendering. Fonts are Nothing's own from `/system/fonts` (`Ndot-57`, `NDot57Caps`,
`LetteraMonoLL`), with a monospace fallback on other phones. Layout is specified in dp and scaled
to whatever size the launcher reports (`OPTION_APPWIDGET_SIZES`), dropping rows if it can't fit.

## Building

Self-contained toolchain in `~/.local/android-toolchain` (JDK 17, Gradle 8.11.1, SDK 35), no root:

```
./build.sh        # → app/build/outputs/apk/release/app-release.apk (signed with ~/.config/t3usage/release.jks)
./install.sh      # adb over wireless debugging if connected (hadb), else Termux + installer prompt
```

`hadb` = `~/.local/bin/hadb`, adb pinned to the phone's Tailscale IP:port. Re-pair with
`adb pair <ip:port> <code>` if the phone forgets it. Debug hooks (adb):

```
hadb shell am start -n ca.heeney.t3usage/.SettingsActivity --activity-clear-task --ez refresh true
hadb shell am start -n ca.heeney.t3usage/.SettingsActivity --activity-clear-task --ei preview_w 177 --ei preview_h 282
```

The preview lands in `Download/` on the phone as a PNG.
