package dev.starsector.preflight.agent;

import java.awt.Component;
import java.awt.Container;
import java.awt.Window;
import java.awt.event.ActionListener;
import java.util.ArrayDeque;
import java.util.Deque;
import javax.swing.AbstractButton;
import javax.swing.SwingUtilities;

/**
 * Presses Starsector's own Play button so a benchmark does not need a human to.
 *
 * <p>The button is found by identity rather than by label: {@code StarfarerLauncher} implements
 * {@link ActionListener} and registers itself on the control that starts the game, so the button
 * whose listeners include the launcher is that control by construction. Matching on button text
 * would break on a renamed or localised build and, worse, could match the wrong control silently.
 *
 * <p>Clicking it is deliberately preferred over calling the launcher's static start method
 * directly. The handler reads the resolution, fullscreen and sound settings off the launcher's own
 * state before starting the game, so going through the button keeps the launch identical to the one
 * an operator produces. Calling the start method would mean supplying those four values ourselves
 * and quietly measuring a different configuration than every earlier campaign.
 *
 * <p>It also answers "when is it safe to click" exactly. The harness otherwise waits for a texture
 * to load and then for the log to fall quiet for six seconds, which is a proxy and a guess. A
 * button that exists, is showing, and is enabled is the thing those were approximating.
 *
 * <p>Fail-open throughout: if the button never appears within the deadline this gives up silently
 * and the operator clicks, exactly as before.
 */
final class LauncherAutoPlay {
    private static final String LAUNCHER_CLASS = "com.fs.starfarer.StarfarerLauncher";
    private static final long POLL_MILLIS = 100L;

    private LauncherAutoPlay() {
    }

    static void arm(long timeoutMillis) {
        Thread thread = new Thread(() -> run(timeoutMillis), "Preflight-AutoPlay");
        thread.setDaemon(true);
        thread.start();
    }

    private static void run(long timeoutMillis) {
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        try {
            while (System.nanoTime() < deadline) {
                AbstractButton play = findPlayButton();
                if (play != null) {
                    click(play);
                    return;
                }
                Thread.sleep(POLL_MILLIS);
            }
            log("Play button never appeared within " + timeoutMillis + "ms; click it yourself.");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable error) {
            log("Auto-play disabled: " + error);
        }
    }

    private static void click(AbstractButton play) throws Exception {
        // On the event dispatch thread, because that is where a real click would arrive and the
        // handler touches Swing state. Waiting for it to run also means the log line below is only
        // written once the click has actually been delivered.
        SwingUtilities.invokeAndWait(play::doClick);
        log("Pressed the launcher's Play button.");
    }

    private static AbstractButton findPlayButton() {
        for (Window window : Window.getWindows()) {
            if (!window.isShowing()) {
                continue;
            }
            AbstractButton found = search(window);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** Breadth-first so a shallow match wins, and iterative so a deep hierarchy cannot overflow. */
    static AbstractButton search(Container root) {
        Deque<Container> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            Container container = queue.removeFirst();
            for (Component child : container.getComponents()) {
                if (child instanceof AbstractButton button && startsTheGame(button)) {
                    return button;
                }
                if (child instanceof Container nested) {
                    queue.addLast(nested);
                }
            }
        }
        return null;
    }

    private static boolean startsTheGame(AbstractButton button) {
        // Enabled and showing are the readiness condition this whole mechanism exists to replace:
        // a button that is not yet either is exactly the window in which an early click is unsafe.
        if (!button.isEnabled() || !button.isShowing()) {
            return false;
        }
        for (ActionListener listener : button.getActionListeners()) {
            if (LAUNCHER_CLASS.equals(listener.getClass().getName())) {
                return true;
            }
        }
        return false;
    }

    private static void log(String message) {
        System.err.println("[Preflight] " + message);
    }
}
