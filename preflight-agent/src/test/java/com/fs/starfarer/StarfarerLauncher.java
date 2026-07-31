package com.fs.starfarer;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

/**
 * Stand-in for the installed launcher, present only so a test can build a button that looks to
 * {@code LauncherAutoPlay} exactly like the real Play control does: same class name on the
 * registered listener. Nothing is copied from the game's archives -- the only thing that matters
 * here is the fully qualified name, which is the identity the search matches on.
 */
public final class StarfarerLauncher implements ActionListener {
    public int clicks;

    @Override
    public void actionPerformed(ActionEvent event) {
        clicks++;
    }
}
