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

    private void capture(String name) throws Exception {
        BufferedImage image = robot.createScreenCapture(screen);
        Path target = output.resolve(name);
        if (!ImageIO.write(image, "png", target.toFile()) || Files.size(target) < 10_000) {
            throw new IllegalStateException("Invalid screenshot: " + target);
        }
    }

    private void tap(int key) {
        robot.keyPress(key);
        robot.keyRelease(key);
    }

    private void type(String value) {
        for (char c : value.toCharArray()) {
            int key = KeyEvent.getExtendedKeyCodeForChar(c);
            if (key == KeyEvent.VK_UNDEFINED) throw new IllegalArgumentException("Unsupported character: " + c);
            robot.keyPress(key);
            robot.keyRelease(key);
        }
    }

    private void command(String command) throws InterruptedException {
        tap(KeyEvent.VK_SLASH);
        Thread.sleep(400);
        type(command.substring(1));
        tap(KeyEvent.VK_ENTER);
        Thread.sleep(3500);
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

    private void run() throws Exception {
        Thread.sleep(2500);
        capture("01-overlay-off.png");
        tap(KeyEvent.VK_G);
        awaitClientPayload();
        Thread.sleep(1500);
        capture("02-overlay-on.png");
        command("/traces dev connected");
        Thread.sleep(2500);
        capture("03-guidance-connected.png");
        command("/traces dev disconnected");
        Thread.sleep(2500);
        capture("04-guidance-disconnected.png");
        command("/traces dev occlusion");
        Thread.sleep(1500);
        capture("05-depth-occlusion.png");
        tap(KeyEvent.VK_E);
        Thread.sleep(1500);
        capture("06-gui-open.png");
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
