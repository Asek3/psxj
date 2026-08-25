package nanolive.psxj.platform.render;

import nanolive.psxj.emu.api.RenderBackend;
import nanolive.psxj.emu.video.GpuFrame;
import nanolive.psxj.util.Log;

import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.EnumSet;

public final class AwtRenderer implements RenderBackend, WindowOverlayTarget {

    private final String title;
    private final int width;
    private final int height;

    private volatile JFrame frame;
    private volatile ImagePanel panel;
    private volatile CloseRequestHandler closeRequestHandler;
    private volatile KeyEventHandler keyEventHandler;
    private volatile HostKeyEventHandler hostKeyEventHandler;
    private volatile PointerEventHandler pointerEventHandler;
    private volatile GameOverlay gameOverlay;
    private final EnumSet<InputKey> pressedInputKeys = EnumSet.noneOf(InputKey.class);
    private final EnumSet<HostKey> consumedHostKeys = EnumSet.noneOf(HostKey.class);
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean notifyingClose = new AtomicBoolean();

    public AwtRenderer(String title, int width, int height) {
        this.title = title;
        this.width = width;
        this.height = height;
    }

    @Override
    public void open() {
        if (frame != null) {
            return;
        }
        CountDownLatch latch = new CountDownLatch(1);
        SwingUtilities.invokeLater(() -> {
            JFrame window = new JFrame(title);
            window.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
            ImagePanel imagePanel = new ImagePanel();
            imagePanel.setPreferredSize(new Dimension(width, height));
            imagePanel.setFocusable(true);
            imagePanel.addKeyListener(new KeyAdapter() {
                @Override
                public void keyPressed(KeyEvent event) {
                    dispatchAwtKey(event.getKeyCode(), true);
                }

                @Override
                public void keyReleased(KeyEvent event) {
                    dispatchAwtKey(event.getKeyCode(), false);
                }
            });
            imagePanel.addFocusListener(new FocusAdapter() {
                @Override
                public void focusLost(FocusEvent event) {
                    releasePressedKeys();
                }
            });
            MouseAdapter mouseButtons = new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent event) {
                    dispatchPointer(imagePanel, event, PointerAction.DOWN);
                }

                @Override
                public void mouseReleased(MouseEvent event) {
                    dispatchPointer(imagePanel, event, PointerAction.UP);
                }
            };
            imagePanel.addMouseListener(mouseButtons);
            imagePanel.addMouseMotionListener(new MouseMotionAdapter() {
                @Override
                public void mouseMoved(MouseEvent event) {
                    dispatchPointer(imagePanel, event, PointerAction.MOVE);
                }

                @Override
                public void mouseDragged(MouseEvent event) {
                    dispatchPointer(imagePanel, event, PointerAction.MOVE);
                }
            });
            imagePanel.addComponentListener(new java.awt.event.ComponentAdapter() {
                @Override
                public void componentResized(java.awt.event.ComponentEvent event) {
                    imagePanel.redrawOverlay(gameOverlay);
                }
            });
            window.setLayout(new BorderLayout());
            window.add(imagePanel, BorderLayout.CENTER);
            window.pack();
            window.setLocationByPlatform(true);
            window.setAlwaysOnTop(true);
            window.setVisible(true);
            window.toFront();
            window.requestFocus();
            imagePanel.requestFocusInWindow();
            window.setAlwaysOnTop(false);
            window.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosing(WindowEvent e) {
                    CloseRequestHandler handler = closeRequestHandler;
                    boolean accepted = handler == null;
                    if (notifyingClose.compareAndSet(false, true)) {
                        try {
                            accepted = handler == null || handler.shouldClose();
                        } catch (Throwable t) {
                            Log.warn("Render close handler failed: " + t.getMessage());
                        } finally {
                            notifyingClose.set(false);
                        }
                    }
                    if (!accepted) {
                        imagePanel.requestFocusInWindow();
                        return;
                    }
                    releasePressedKeys();
                    closed.set(true);
                    window.dispose();
                }

                @Override
                public void windowClosed(WindowEvent e) {
                    if (frame == window) {
                        frame = null;
                        panel = null;
                    }
                }
            });
            frame = window;
            panel = imagePanel;
            closed.set(false);
            latch.countDown();
        });
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void presentFrame(GpuFrame frame) {
        ImagePanel target = panel;
        if (target == null || frame == null || closed.get()) {
            return;
        }
        target.updateFrame(frame);
    }

    @Override
    public boolean isRenderSurfaceAvailable() {
        return panel != null && !closed.get();
    }

    @Override
    public void requestAttention() {
        JFrame window = frame;
        if (window == null) {
            return;
        }
        SwingUtilities.invokeLater(() -> {
            if (frame != null) {
                frame.setAlwaysOnTop(true);
                frame.toFront();
                frame.requestFocus();
                frame.setAlwaysOnTop(false);
            }
        });
    }

    @Override
    public void setCloseRequestHandler(CloseRequestHandler handler) {
        this.closeRequestHandler = handler;
    }

    @Override
    public void setKeyEventHandler(KeyEventHandler handler) {
        keyEventHandler = handler;
    }

    @Override
    public void setHostKeyEventHandler(HostKeyEventHandler handler) {
        hostKeyEventHandler = handler;
    }

    @Override
    public void setPointerEventHandler(PointerEventHandler handler) {
        pointerEventHandler = handler;
    }

    @Override
    public void setPointerCursor(PointerCursor cursor) {
        ImagePanel target = panel;
        if (target == null) {
            return;
        }
        int awtCursor = cursor == PointerCursor.POINTING_HAND
            ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR;
        SwingUtilities.invokeLater(() -> target.setCursor(Cursor.getPredefinedCursor(awtCursor)));
    }

    @Override
    public void setGameOverlay(GameOverlay overlay) {
        gameOverlay = overlay;
    }

    @Override
    public void redrawOverlay() {
        ImagePanel target = panel;
        if (target != null) {
            SwingUtilities.invokeLater(() -> target.redrawOverlay(gameOverlay));
        }
    }

    @Override
    public int overlayRefreshRateHz() {
        JFrame window = frame;
        if (window != null && window.getGraphicsConfiguration() != null) {
            int rate = window.getGraphicsConfiguration().getDevice().getDisplayMode().getRefreshRate();
            if (rate > 0) return Math.clamp(rate, 30, 360);
        }
        return WindowOverlayTarget.super.overlayRefreshRateHz();
    }

    @Override
    public void close() {
        releasePressedKeys();
        JFrame window = frame;
        frame = null;
        panel = null;
        closed.set(true);
        if (window != null) {
            SwingUtilities.invokeLater(window::dispose);
        }
    }

    private synchronized void dispatchAwtKey(int keyCode, boolean pressed) {
        HostKey hostKey = mapHostKey(keyCode);
        boolean consumed = hostKey != null && dispatchHostKey(hostKey, pressed);
        InputKey inputKey = mapInputKey(keyCode);
        if (!consumed && inputKey != null) {
            boolean changed = pressed
                ? pressedInputKeys.add(inputKey)
                : pressedInputKeys.remove(inputKey);
            KeyEventHandler handler = keyEventHandler;
            if (changed && handler != null) {
                handler.handle(inputKey, pressed);
            }
        }
    }

    private boolean dispatchHostKey(HostKey key, boolean pressed) {
        HostKeyEventHandler handler = hostKeyEventHandler;
        if (pressed) {
            if (consumedHostKeys.contains(key)) {
                return true;
            }
            boolean consumed = handler != null && handler.handle(key, true);
            if (consumed) consumedHostKeys.add(key);
            return consumed;
        }
        boolean consumed = consumedHostKeys.remove(key);
        boolean releasedConsumed = handler != null && handler.handle(key, false);
        return consumed || releasedConsumed;
    }

    private void dispatchPointer(ImagePanel source, MouseEvent event, PointerAction action) {
        PointerEventHandler handler = pointerEventHandler;
        if (handler == null) return;
        float x = event.getX() / (float) Math.max(1, source.getWidth());
        float y = event.getY() / (float) Math.max(1, source.getHeight());
        handler.handle(new PointerEvent(x, y, event.getButton(), action));
        if (action == PointerAction.DOWN) source.requestFocusInWindow();
    }

    private synchronized void releasePressedKeys() {
        KeyEventHandler inputHandler = keyEventHandler;
        if (inputHandler != null) {
            for (InputKey key : pressedInputKeys) inputHandler.handle(key, false);
        }
        pressedInputKeys.clear();
        HostKeyEventHandler hostHandler = hostKeyEventHandler;
        if (hostHandler != null) {
            for (HostKey key : consumedHostKeys) hostHandler.handle(key, false);
        }
        consumedHostKeys.clear();
    }

    private static HostKey mapHostKey(int keyCode) {
        return switch (keyCode) {
            case KeyEvent.VK_F5 -> HostKey.SAVE_STATE;
            case KeyEvent.VK_F8 -> HostKey.LOAD_STATE;
            case KeyEvent.VK_ESCAPE -> HostKey.CANCEL;
            case KeyEvent.VK_ENTER -> HostKey.CONFIRM;
            case KeyEvent.VK_1 -> HostKey.SLOT_1;
            case KeyEvent.VK_2 -> HostKey.SLOT_2;
            case KeyEvent.VK_3 -> HostKey.SLOT_3;
            case KeyEvent.VK_4 -> HostKey.SLOT_4;
            case KeyEvent.VK_5 -> HostKey.SLOT_5;
            case KeyEvent.VK_6 -> HostKey.SLOT_6;
            case KeyEvent.VK_7 -> HostKey.SLOT_7;
            case KeyEvent.VK_8 -> HostKey.SLOT_8;
            case KeyEvent.VK_9 -> HostKey.SLOT_9;
            default -> null;
        };
    }

    private static InputKey mapInputKey(int keyCode) {
        return switch (keyCode) {
            case KeyEvent.VK_UP -> InputKey.UP;
            case KeyEvent.VK_RIGHT -> InputKey.RIGHT;
            case KeyEvent.VK_DOWN -> InputKey.DOWN;
            case KeyEvent.VK_LEFT -> InputKey.LEFT;
            case KeyEvent.VK_ENTER -> InputKey.START;
            case KeyEvent.VK_SPACE -> InputKey.SELECT;
            case KeyEvent.VK_X -> InputKey.CROSS;
            case KeyEvent.VK_Z -> InputKey.SQUARE;
            case KeyEvent.VK_S -> InputKey.CIRCLE;
            case KeyEvent.VK_A -> InputKey.TRIANGLE;
            case KeyEvent.VK_Q -> InputKey.L1;
            case KeyEvent.VK_W -> InputKey.R1;
            case KeyEvent.VK_1 -> InputKey.L2;
            case KeyEvent.VK_2 -> InputKey.R2;
            default -> null;
        };
    }

    private static final class ImagePanel extends JPanel {
        private BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        private BufferedImage overlayImage;

        synchronized void updateFrame(GpuFrame frame) {
            BufferedImage current = image;
            if (current.getWidth() != frame.width() || current.getHeight() != frame.height()) {
                current = new BufferedImage(frame.width(), frame.height(), BufferedImage.TYPE_INT_RGB);
                image = current;
            }
            int[] dst = ((DataBufferInt) current.getRaster().getDataBuffer()).getData();
            int[] src = frame.pixels();
            System.arraycopy(src, 0, dst, 0, Math.min(dst.length, src.length));
            repaint();
        }

        synchronized void redrawOverlay(GameOverlay overlay) {
            int width = Math.max(1, getWidth());
            int height = Math.max(1, getHeight());
            if (overlay == null || !overlay.isVisible()) {
                overlayImage = null;
                repaint();
                return;
            }
            BufferedImage current = overlayImage;
            if (current == null || current.getWidth() != width || current.getHeight() != height) {
                current = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
                overlayImage = current;
            }
            Graphics2D graphics = current.createGraphics();
            try {
                graphics.setComposite(java.awt.AlphaComposite.Clear);
                graphics.fillRect(0, 0, width, height);
                graphics.setComposite(java.awt.AlphaComposite.SrcOver);
                overlay.render(graphics, width, height);
            } finally {
                graphics.dispose();
            }
            repaint();
        }

        @Override
        protected synchronized void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                g2.drawImage(image, 0, 0, getWidth(), getHeight(), null);
                if (overlayImage != null) {
                    g2.drawImage(overlayImage, 0, 0, null);
                }
            } finally {
                g2.dispose();
            }
        }
    }
}
