# Fold Motion

A fold transition for the Galaxy Z Fold8 Ultra. Three styles, chosen in the app:

- **Visconti**: a lacquer arch closes in on the crease (closing); dark glass with Visconti light inside it opens into a lit bezel that seats and slides away (opening).
- **Giulietta**: the same glass opening, woken by the Giulietta's rear lamps. The right ring sweeps round where your fingers left the cover, its beaded bar runs in to the crease, the light crosses, and the left lamp completes as the halves' hold gives.
- **Otto** (called Fanale until 28 Nov 2026): the lamps with figure-8 joints, a nod to the Fold 8: the bars leave their rings at opposite points, so the light traces the waist of an 8. Both lamps run at once, opening and closing: the rings sweep round together, the light runs in along both bars and meets in the middle.

- **Travaso** (27 Nov 2026, replacing Catenaria and Membrana): your screen as a liquid, poured between two vessels. Opening, it pours out of the crease as a column and spreads to fill the glass, its corners seating into the screen's own; closing drains it back into the crease and, if the cover lights unlocked, pours it onto the cover from the hinge edge. The meniscus is an AGSL GPU shader; its light takes your wallpaper's Material You colours by default, or champagne, rosso or Visconti.

**Concentrato** (on by default): every style reads how fast you folded and keeps less detail the faster you go; the movement itself always stays. The settings screen plays each transition live and carries the design charter and notes.

The effect learns your closing speed and fits itself inside it, measures its own frame timing, and restarts itself if the phone kills it.

## The Kit

Colours, type, the settings controls, the doctrine and crash reports come from Cerniera's Kit
(cerniera-studio/kit), a git submodule at `kit/` pinned to a commit. Clone with
`git clone --recurse-submodules`; to take the Kit's latest, run `git submodule update --remote kit`
and commit. The transitions themselves (the overlay, the lamps, the liquid) are Fold Motion's own.

## Get the APK (no Android Studio needed)

1. Push this folder to a GitHub repository.
2. Open the repository's **Actions** tab. The **Build APK** workflow runs on every push.
3. When it goes green, each build on main is published under **Releases** as `FoldMotion.apk`
   (builds from other branches appear as pre-releases).
4. Download it on the phone and open the `.apk` to the phone and open it. Allow installs from that source when asked.

## First run on the phone

1. Open **Fold Motion**. The hinge readout should move as you open and close the phone.
2. Tap **Allow display over other apps** and switch it on.
3. Tap **Start animation**. A silent notification confirms it is running.

Timing follows Developer options > Animator duration scale.

## Tuning

All values live in `MotionConfig.kt`. Change a number, push, and Actions builds a new APK.

## Limits

- This is an overlay, not a system animation. One UI's own display switch still happens underneath.
- The overlay cannot draw over the lock screen.
- Untested on hardware at the time of writing.
