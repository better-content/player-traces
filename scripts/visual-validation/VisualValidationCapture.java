import java.awt.AWTException;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

public final class VisualValidationCapture {
    private static final Rectangle WORLD_SAMPLE = new Rectangle(160, 90, 960, 540);
    private final Robot robot;
    private final Rectangle screen;
    private final Path output;
    private final Path clientLog;

    private VisualValidationCapture(Path output, Path clientLog) throws Exception {
        this.robot = new Robot();
        this.robot.setAutoDelay(35);
        Dimension size = Toolkit.getDefaultToolkit().getScreenSize();
        this.screen = new Rectangle(size);
        this.output = output;
        this.clientLog = clientLog;
        Files.createDirectories(output);
    }

    private BufferedImage capture(String name) throws Exception {
        BufferedImage image = robot.createScreenCapture(screen);
        Path target = output.resolve(name);
        if (!ImageIO.write(image, "png", target.toFile()) || Files.size(target) < 10_000) {
            throw new IllegalStateException("Invalid screenshot: " + target);
        }
        return image;
    }

    private static double meanLuminance(BufferedImage image) {
        return meanLuminance(image, WORLD_SAMPLE);
    }

    private static double meanLuminance(BufferedImage image, Rectangle sample) {
        long samples = 0;
        double luminance = 0.0;
        int maxX = Math.min(sample.x + sample.width, image.getWidth());
        int maxY = Math.min(sample.y + sample.height, image.getHeight());
        for (int y = sample.y; y < maxY; y += 4) {
            for (int x = sample.x; x < maxX; x += 4) {
                int rgb = image.getRGB(x, y);
                int red = (rgb >>> 16) & 0xff;
                int green = (rgb >>> 8) & 0xff;
                int blue = rgb & 0xff;
                luminance += 0.2126 * red + 0.7152 * green + 0.0722 * blue;
                samples++;
            }
        }
        return luminance / samples;
    }

    private static void assertOverlayTreatment(BufferedImage disabled, BufferedImage enabled) {
        Rectangle center = new Rectangle(400, 170, 480, 260);
        Rectangle leftEdge = new Rectangle(0, 160, 80, 320);
        Rectangle rightEdge = new Rectangle(1200, 160, 80, 320);
        double centerRatio = meanLuminance(enabled, center) / meanLuminance(disabled, center);
        double leftRatio = meanLuminance(enabled, leftEdge) / meanLuminance(disabled, leftEdge);
        double rightRatio = meanLuminance(enabled, rightEdge) / meanLuminance(disabled, rightEdge);
        double edgeRatio = (leftRatio + rightRatio) / 2.0;
        if (centerRatio < 0.86 || centerRatio > 1.02 || edgeRatio < 0.40 || edgeRatio >= centerRatio - 0.08) {
            throw new IllegalStateException(
                "Trace Sight treatment is outside contrast bounds: centerRatio=" + centerRatio
                    + ", edgeRatio=" + edgeRatio + ", leftRatio=" + leftRatio + ", rightRatio=" + rightRatio
            );
        }
    }

    private static void assertViewportPreserved(BufferedImage disabled, BufferedImage enabled) {
        double disabledLuminance = meanLuminance(disabled);
        double enabledLuminance = meanLuminance(enabled);
        if (disabledLuminance < 1.0 || enabledLuminance < disabledLuminance * 0.55) {
            throw new IllegalStateException(
                "Traces overlay blackened the world viewport: disabled=" + disabledLuminance
                    + ", enabled=" + enabledLuminance
            );
        }
    }

    private void tap(int key) {
        robot.keyPress(key);
        robot.keyRelease(key);
    }

    private void type(String value) {
        for (char c : value.toCharArray()) {
            boolean shifted = Character.isUpperCase(c);
            int key = KeyEvent.getExtendedKeyCodeForChar(shifted ? Character.toLowerCase(c) : c);
            if (key == KeyEvent.VK_UNDEFINED) throw new IllegalArgumentException("Unsupported character: " + c);
            if (shifted) robot.keyPress(KeyEvent.VK_SHIFT);
            robot.keyPress(key);
            robot.keyRelease(key);
            if (shifted) robot.keyRelease(KeyEvent.VK_SHIFT);
        }
    }

    private void command(String command) throws InterruptedException {
        tap(KeyEvent.VK_SLASH);
        Thread.sleep(400);
        type(command.substring(1));
        tap(KeyEvent.VK_ENTER);
        Thread.sleep(3500);
    }

    private void commandFast(String command) throws InterruptedException {
        tap(KeyEvent.VK_SLASH);
        Thread.sleep(400);
        type(command.substring(1));
        tap(KeyEvent.VK_ENTER);
        Thread.sleep(700);
    }

    private void awaitNoteEcho() throws Exception {
        long deadline = System.nanoTime() + 8_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (Files.exists(clientLog)) {
                String log = Files.readString(clientLog);
                int start = log.lastIndexOf("TRACES_VISUAL_DISCONNECTED");
                if (start >= 0 && log.substring(start).contains("TRACES_ANNOTATION_ECHO_CACHED")
                    && log.substring(start).contains("noteEchoes=1")) return;
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("Timed out waiting for annotation echo playback in " + clientLog);
    }

    private void awaitClientPayload() throws Exception {
        long deadline = System.nanoTime() + 30_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (Files.exists(clientLog)) {
                String log = Files.readString(clientLog);
                if (log.contains("TRACES_VISUAL_READY") && log.contains("annotations=1")) return;
            }
            Thread.sleep(250);
        }
        throw new IllegalStateException("Timed out waiting for client payload readiness in " + clientLog);
    }

    private void awaitPlayerCapture() throws Exception {
        long deadline = System.nanoTime() + 30_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (Files.exists(clientLog)) {
                String log = Files.readString(clientLog);
                int verified = log.lastIndexOf("TRACES_PLAYER_CAPTURE_VERIFIED");
                if (verified >= 0 && log.substring(verified).contains("TRACES_MVP_ACCEPTED")
                    && log.substring(verified).contains("notes=0")) return;
            }
            Thread.sleep(250);
        }
        throw new IllegalStateException("Timed out waiting for player-generated trace payload in " + clientLog);
    }

    private void run() throws Exception {
        Thread.sleep(2500);
        command("/traces dev preparecapture");
        Thread.sleep(1000);
        BufferedImage overlayOff = capture("01-overlay-off.png");
        tap(KeyEvent.VK_G);
        Thread.sleep(3500);
        BufferedImage overlayTreatment = capture("02-overlay-treatment.png");
        assertViewportPreserved(overlayOff, overlayTreatment);
        assertOverlayTreatment(overlayOff, overlayTreatment);
        tap(KeyEvent.VK_G);
        Thread.sleep(300);
        robot.keyPress(KeyEvent.VK_S);
        Thread.sleep(650);
        robot.keyRelease(KeyEvent.VK_S);
        command("/traces dev captureyaw 17");
        robot.keyPress(KeyEvent.VK_S);
        Thread.sleep(650);
        robot.keyRelease(KeyEvent.VK_S);
        command("/traces dev captureyaw 33");
        robot.keyPress(KeyEvent.VK_S);
        robot.keyPress(KeyEvent.VK_D);
        Thread.sleep(850);
        robot.keyRelease(KeyEvent.VK_D);
        robot.keyRelease(KeyEvent.VK_S);
        command("/traces dev captureyaw 58");
        robot.keyPress(KeyEvent.VK_S);
        robot.keyPress(KeyEvent.VK_D);
        Thread.sleep(850);
        robot.keyRelease(KeyEvent.VK_D);
        robot.keyRelease(KeyEvent.VK_S);
        command("/traces dev verifycapture");
        command("/traces dev captureyaw 0");
        tap(KeyEvent.VK_G);
        awaitPlayerCapture();
        Thread.sleep(1500);
        BufferedImage overlayOn = capture("03-player-generated-precise-yaw.png");
        assertViewportPreserved(overlayOff, overlayOn);
        command("/traces dev fixture");
        awaitClientPayload();
        command("/traces dev connected");
        Thread.sleep(2500);
        capture("04-guidance-connected.png");
        robot.keyPress(KeyEvent.VK_W);
        robot.keyPress(KeyEvent.VK_D);
        Thread.sleep(650);
        robot.keyRelease(KeyEvent.VK_D);
        Thread.sleep(1450);
        robot.keyRelease(KeyEvent.VK_W);
        Thread.sleep(2500);
        Thread.sleep(1500);
        capture("04b-guidance-followed-xp.png");
        commandFast("/traces dev disconnected");
        awaitNoteEcho();
        capture("05-guidance-disconnected.png");
        command("/traces dev occlusion");
        Thread.sleep(1500);
        capture("06-depth-occlusion.png");
        tap(KeyEvent.VK_N);
        Thread.sleep(1500);
        capture("07-annotation-editor.png");
        tap(KeyEvent.VK_ESCAPE);
        robot.keyPress(KeyEvent.VK_ALT);
        tap(KeyEvent.VK_F4);
        robot.keyRelease(KeyEvent.VK_ALT);
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("usage: VisualValidationCapture <output-dir> <client-log>");
        new VisualValidationCapture(Path.of(args[0]), Path.of(args[1])).run();
    }
}
