package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.awt.GraphicsEnvironment;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JPanel;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class LauncherAutoPlayTest {
    @Test
    void findsThePlayButtonByListenerIdentityAndNotByLabel() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "needs a display");

        com.fs.starfarer.StarfarerLauncher launcher = new com.fs.starfarer.StarfarerLauncher();
        JFrame frame = new JFrame("launcher");
        try {
            JPanel outer = new JPanel();
            JPanel inner = new JPanel();
            // A decoy that says the right thing and does nothing, which is precisely the control a
            // text match would find. The real button is nested deeper and named something else.
            JButton decoy = new JButton("Play Starsector");
            JButton real = new JButton("Ok");
            real.addActionListener(launcher);
            inner.add(real);
            outer.add(decoy);
            outer.add(inner);
            frame.add(outer);
            frame.pack();
            frame.setVisible(true);

            assertSame(real, LauncherAutoPlay.search(frame));
        } finally {
            frame.dispose();
        }
    }

    @Test
    void ignoresAButtonThatIsNotYetSafeToPress() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "needs a display");

        // Disabled is the state the six-second quiet window was approximating. A launcher that has
        // drawn its button but is not ready for it must read as "not found", so the caller keeps
        // waiting rather than delivering a click into a half-built launcher.
        JFrame frame = new JFrame("launcher");
        try {
            JButton real = new JButton("Ok");
            real.addActionListener(new com.fs.starfarer.StarfarerLauncher());
            real.setEnabled(false);
            frame.add(real);
            frame.pack();
            frame.setVisible(true);

            assertNull(LauncherAutoPlay.search(frame));
        } finally {
            frame.dispose();
        }
    }
}
