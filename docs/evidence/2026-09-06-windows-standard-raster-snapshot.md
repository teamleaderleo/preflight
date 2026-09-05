# Windows standard raster snapshot trial

Objective: reduce ordinary Recommended texture-loading time while retaining the original
large-image converter, GL policy, 1024 ceiling, main-thread ownership and late resources.
Owner: current Codex task. Phase: correctness verification before ordinary native measurements.
Finish: retain only justified changes, merge main, restore normal Windows task and retire builds.

Baseline main `4831eae286369ac8eb15bf985fc4bbb2ff4dfeba`, installed JAR
`369d43b415829c5082c29d1762adb9d8fdb73f6cf73d6406087740bd38534cb9`.
Most recent ordinary interactive menus: 18.797 / 18.413 s. These are a reference, not a paired
control or evidence of a guaranteed sub-18 result.

An untouched coherent carrier currently materializes a standard raster, retains it, then copies
it for `BufferedImage.getData()`. The candidate constructs the independent snapshot directly from
immutable prepared pixels. It uses the same JDK raster/sample-model/data-buffer classes, with
no custom `getPixel` implementation. After any raster exposure or mutation, it continues through
the existing materialized image. Every call still receives an independent snapshot.

This differs from the rejected custom-raster experiment: the stock converter sees its ordinary
standard raster implementation. No converter, cleanup, handler, cache, scheduling or GL code changes.
RGB/RGBA tests cover independent snapshot mutation, subsequent carrier mutation, concrete raster
classes and absence of unnecessary retained materialization. No startup speedup is claimed yet.
