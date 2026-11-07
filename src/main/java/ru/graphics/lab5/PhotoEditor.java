package ru.graphics.lab5;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;


public final class PhotoEditor extends JFrame {
    private BufferedImage original;  // Неизменяемый оригинал для сравнения и сброса.
    private BufferedImage committed; // Последнее применённое состояние.
    private BufferedImage displayed; // Предпросмотр; именно он сохраняется в файл.
    // Бинаризация необратимо теряет оттенки. Для её сброса нужен сохранённый
    // снимок входа, а не попытка восстановить цвета из чёрно-белых пикселей.
    private BufferedImage beforeBinarization;
    private Rectangle selection;
    private File openedFile;
    private final Deque<HistoryState> history = new ArrayDeque<>();
    private long historyBytes;
    private static final long HISTORY_LIMIT_BYTES = 160L * 1024 * 1024;

    private final ImagePanel originalView = new ImagePanel(null);
    private final ImagePanel resultView = new ImagePanel(this::selectRegion);
    private final HistogramPanel histogram = new HistogramPanel();
    private final JLabel status = new JLabel("Откройте изображение");
    private final JLabel selectionLabel = new JLabel("Область: всё изображение");
    private final JLabel histogramStats = new JLabel(" ");
    private final JLabel brightnessValue = new JLabel("0");
    private final JLabel contrastValue = new JLabel("1.00");
    private final JLabel gammaValue = new JLabel("1.00");
    private final JSlider brightness = new JSlider(-255, 255, 0);
    private final JSlider contrast = new JSlider(0, 300, 100);
    private final JSlider gamma = new JSlider(20, 300, 100);
    private final JSpinner window = new JSpinner(new SpinnerNumberModel(31, 3, 501, 2));
    private final JSpinner bradleyPercent = new JSpinner(new SpinnerNumberModel(15, 0, 99, 1));
    private final JSpinner wolfK = new JSpinner(new SpinnerNumberModel(0.5, 0.0, 1.0, 0.05));
    private final JButton open = new JButton("Открыть…");
    private final JButton save = new JButton("Сохранить…");
    private final JButton undo = new JButton("Отменить");
    private final JButton reset = new JButton("Вернуть оригинал");
    private final JButton apply = new JButton("Применить коррекцию");
    private final JButton cancelPreview = new JButton("Сбросить ползунки");
    private final JButton clearSelection = new JButton("Снять выделение");
    private final JButton resetBinarization = new JButton("Сбросить бинаризацию");
    private final List<JButton> operationButtons = new ArrayList<>();
    private final Timer debounce;
    private SwingWorker<Render, Void> worker;
    private long revision;
    private boolean updatingControls, busy, exclusive, previewPending;

    private record Render(BufferedImage image, long[] before, long[] after, String detail) { }
    private record HistoryState(BufferedImage image, BufferedImage beforeBinarization) { }

    private enum Operation {
        GRAY("Оттенки серого"), NEGATIVE("Негатив"), OTSU("Оцу · общий порог"),
        BRADLEY("Брэдли · локальный порог"), WOLF("Кристиан Вольф (Wolf–Jolion)"),
        STRETCH("Растянуть диапазон"), EQUALIZE("Эквализация");

        final String title;
        Operation(String title) { this.title = title; }
        boolean isBinarization() { return this == OTSU || this == BRADLEY || this == WOLF; }
    }

    public PhotoEditor() {
        super("ЛР 5 · Обработка фотоизображений");
        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(980, 700));
        setSize(1440, 920);
        setLocationRelativeTo(null);
        debounce = new Timer(160, e -> preview());
        debounce.setRepeats(false);
        buildInterface();
        bindActions();
        updateActions();
    }

    private void buildInterface() {
        JPanel root = new JPanel(new BorderLayout(12, 10));
        root.setBorder(new EmptyBorder(12, 14, 10, 14));
        setContentPane(root);

        JToolBar toolbar = new JToolBar();
        toolbar.setFloatable(false);
        for (JButton button : new JButton[]{open, save, undo, reset}) toolbar.add(button);
        toolbar.addSeparator(new Dimension(24, 1));
        toolbar.add(new JLabel("Масштаб: "));
        JComboBox<String> zoom = new JComboBox<>(new String[]{"Вписать", "25%", "50%", "100%", "200%", "400%"});
        zoom.setMaximumSize(new Dimension(100, 32));
        zoom.addActionListener(e -> {
            double[] values = {0, 0.25, 0.5, 1, 2, 4};
            originalView.setZoom(values[zoom.getSelectedIndex()]);
            resultView.setZoom(values[zoom.getSelectedIndex()]);
        });
        toolbar.add(zoom);
        root.add(toolbar, BorderLayout.NORTH);

        JSplitPane views = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                imageContainer("Исходное изображение", originalView),
                imageContainer("Результат · выделение мышью", resultView));
        views.setResizeWeight(0.5);
        views.setContinuousLayout(true);
        views.setBorder(null);

        JPanel histogramArea = new JPanel(new BorderLayout(0, 3));
        JPanel histogramHeader = new JPanel(new BorderLayout());
        histogramHeader.add(new JLabel("Гистограмма яркости  ·  серый — оригинал, зелёный — результат"), BorderLayout.CENTER);
        JCheckBox log = new JCheckBox("Логарифмическая шкала");
        log.addActionListener(e -> histogram.setLogarithmic(log.isSelected()));
        histogramHeader.add(log, BorderLayout.EAST);
        histogramArea.add(histogramHeader, BorderLayout.NORTH);
        histogramArea.add(histogram, BorderLayout.CENTER);
        histogramArea.add(histogramStats, BorderLayout.SOUTH);

        JSplitPane center = new JSplitPane(JSplitPane.VERTICAL_SPLIT, views, histogramArea);
        center.setResizeWeight(0.78);
        center.setContinuousLayout(true);
        center.setBorder(null);
        root.add(center, BorderLayout.CENTER);

        JPanel tools = new JPanel();
        tools.setLayout(new BoxLayout(tools, BoxLayout.Y_AXIS));
        tools.setBorder(new EmptyBorder(0, 5, 0, 0));
        tools.setPreferredSize(new Dimension(305, 600));
        tools.add(title("Коррекция"));
        tools.add(sliderBlock("Яркость", brightness, brightnessValue,
                "C' = clip(C + Δ). −255: чёрный, +255: белый."));
        tools.add(sliderBlock("Контрастность", contrast, contrastValue,
                "C' = clip(K × (C − среднее) + среднее). 1 — без изменений."));
        tools.add(sliderBlock("Гамма", gamma, gammaValue,
                "C' = 255 × (C / 255)^γ. γ < 1 осветляет, γ > 1 затемняет."));
        tools.add(buttonRow(apply));
        tools.add(buttonRow(cancelPreview));
        tools.add(gap());
        tools.add(title("Цветность"));
        tools.add(buttonRow(operation(Operation.GRAY)));
        tools.add(buttonRow(operation(Operation.NEGATIVE)));
        tools.add(gap());
        tools.add(title("Бинаризация"));
        tools.add(field("Окно (нечётное):", window));
        tools.add(field("Брэдли, t (%):", bradleyPercent));
        tools.add(field("Вольф, k:", wolfK));
        tools.add(buttonRow(operation(Operation.OTSU)));
        tools.add(buttonRow(operation(Operation.BRADLEY)));
        tools.add(buttonRow(operation(Operation.WOLF)));
        resetBinarization.setToolTipText("Вернуть изображение перед последним методом бинаризации, включая прежние коррекции.");
        tools.add(buttonRow(resetBinarization));
        tools.add(gap());
        tools.add(title("Преобразование гистограммы"));
        tools.add(buttonRow(operation(Operation.STRETCH)));
        tools.add(buttonRow(operation(Operation.EQUALIZE)));
        tools.add(gap());
        tools.add(title("Выделенный фрагмент"));
        selectionLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        tools.add(selectionLabel);
        tools.add(buttonRow(clearSelection));
        JLabel hint = new JLabel("<html>Выделите прямоугольник на результате.<br>Все операции и гистограмма относятся<br>к нему. Без выделения — ко всему фото.</html>");
        hint.setBorder(new EmptyBorder(6, 0, 0, 0));
        hint.setForeground(new Color(85, 95, 108));
        hint.setAlignmentX(Component.LEFT_ALIGNMENT);
        tools.add(hint);
        tools.add(Box.createVerticalGlue());
        JScrollPane toolScroll = new JScrollPane(tools);
        toolScroll.setBorder(null);
        toolScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        toolScroll.getVerticalScrollBar().setUnitIncrement(20);
        toolScroll.setPreferredSize(new Dimension(320, 600));
        root.add(toolScroll, BorderLayout.EAST);
        status.setBorder(new EmptyBorder(5, 0, 0, 0));
        root.add(status, BorderLayout.SOUTH);
    }

    private static JPanel imageContainer(String name, ImagePanel view) {
        JPanel panel = new JPanel(new BorderLayout(0, 5));
        panel.add(new JLabel(name), BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(view);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        panel.add(scroll, BorderLayout.CENTER);
        panel.setMinimumSize(new Dimension(160, 160));
        return panel;
    }

    private static JLabel title(String text) {
        JLabel label = new JLabel(text);
        label.setFont(label.getFont().deriveFont(Font.BOLD));
        label.setBorder(new EmptyBorder(3, 0, 7, 0));
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }

    private static Component gap() { return Box.createVerticalStrut(12); }

    private static JPanel buttonRow(JButton button) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(new EmptyBorder(3, 0, 0, 0));
        panel.add(button);
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        return panel;
    }

    private static JPanel field(String label, JSpinner spinner) {
        JPanel panel = new JPanel(new BorderLayout(8, 0));
        panel.add(new JLabel(label), BorderLayout.WEST);
        panel.add(spinner, BorderLayout.CENTER);
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        panel.setBorder(new EmptyBorder(1, 0, 3, 0));
        return panel;
    }

    private static JPanel sliderBlock(String name, JSlider slider, JLabel value, String tooltip) {
        JPanel panel = new JPanel(new BorderLayout());
        JPanel row = new JPanel(new BorderLayout());
        row.add(new JLabel(name), BorderLayout.WEST);
        row.add(value, BorderLayout.EAST);
        panel.add(row, BorderLayout.NORTH);
        panel.add(slider, BorderLayout.CENTER);
        slider.setToolTipText(tooltip);
        slider.setPaintTicks(true);
        slider.setMajorTickSpacing((slider.getMaximum() - slider.getMinimum()) / 4);
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 66));
        return panel;
    }

    private JButton operation(Operation operation) {
        JButton button = new JButton(operation.title);
        button.addActionListener(e -> {
            if (!ready()) return;
            try {
                // commitEdit переносит набранный вручную текст в модель JSpinner.
                window.commitEdit(); bradleyPercent.commitEdit(); wolfK.commitEdit();
                if (intValue(window) % 2 == 0) {
                    throw new IllegalArgumentException("Размер окна должен быть нечётным.");
                }
            } catch (Exception ex) { showError("Проверьте параметры: " + ex.getMessage()); return; }
            BufferedImage input = displayed;
            // Параметры и ROI захватываем в EDT: фоновой поток не читает Swing-компоненты.
            Rectangle roi = selectionSnapshot();
            int size = intValue(window);
            double t = intValue(bradleyPercent) / 100.0;
            double k = ((Number) wolfK.getValue()).doubleValue();
            Function<BufferedImage, BufferedImage> captured = switch (operation) {
                case GRAY -> im -> ImageProcessor.grayscale(im, roi);
                case NEGATIVE -> im -> ImageProcessor.negative(im, roi);
                case OTSU -> im -> ImageProcessor.otsu(im, roi);
                case BRADLEY -> im -> ImageProcessor.bradley(im, roi, size, t);
                case WOLF -> im -> ImageProcessor.wolf(im, roi, size, k);
                case STRETCH -> im -> ImageProcessor.stretch(im, roi);
                case EQUALIZE -> im -> ImageProcessor.equalize(im, roi);
            };
            render(operation.title, input, captured, true, operation.isBinarization());
        });
        operationButtons.add(button);
        return button;
    }

    private void bindActions() {
        open.addActionListener(e -> chooseOpen());
        save.addActionListener(e -> chooseSave());
        brightness.addChangeListener(e -> slidersChanged());
        contrast.addChangeListener(e -> slidersChanged());
        gamma.addChangeListener(e -> slidersChanged());
        apply.addActionListener(e -> {
            if (!ready() || !hasAdjustments()) return;
            pushHistory(committed);
            committed = displayed;
            beforeBinarization = null;
            neutralControls();
            status.setText("Коррекция применена. Можно продолжать обработку.");
            updateActions();
        });
        cancelPreview.addActionListener(e -> { neutralControls(); preview(); });
        clearSelection.addActionListener(e -> selectRegion(null));
        resetBinarization.addActionListener(e -> {
            if (!ready() || beforeBinarization == null) return;
            BufferedImage restored = beforeBinarization;
            // Восстанавливаем точный вход последнего метода: это работает и для
            // ROI, даже если после бинаризации пользователь сменил выделение.
            // Обновление гистограммы и запись в историю выполняет общий render.
            render("Бинаризация сброшена", committed, image -> restored, true);
        });
        undo.addActionListener(e -> undo());
        reset.addActionListener(e -> {
            if (!ready()) return;
            neutralControls();
            render("Возвращён оригинал", committed, im -> original, true);
        });
        shortcut(KeyEvent.VK_O, "open", this::chooseOpen);
        shortcut(KeyEvent.VK_S, "save", this::chooseSave);
        shortcut(KeyEvent.VK_Z, "undo", this::undo);
        getRootPane().registerKeyboardAction(e -> { if (!exclusive) selectRegion(null); },
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
    }

    private void shortcut(int key, String name, Runnable action) {
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(key, KeyEvent.CTRL_DOWN_MASK), name);
        getRootPane().getActionMap().put(name, new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { action.run(); }
        });
    }

    private boolean ready() { return committed != null && !busy && !previewPending; }
    private static int intValue(JSpinner spinner) { return ((Number) spinner.getValue()).intValue(); }
    private Rectangle selectionSnapshot() { return selection == null ? null : new Rectangle(selection); }
    private boolean hasAdjustments() {
        return brightness.getValue() != 0 || contrast.getValue() != 100 || gamma.getValue() != 100;
    }

    private void neutralControls() {
        updatingControls = true;
        brightness.setValue(0); contrast.setValue(100); gamma.setValue(100);
        updatingControls = false;
        updateValues();
        debounce.stop();
        previewPending = false;
    }

    private void updateValues() {
        brightnessValue.setText(String.format("%+d", brightness.getValue()));
        contrastValue.setText(String.format(java.util.Locale.ROOT, "%.2f", contrast.getValue() / 100.0));
        gammaValue.setText(String.format(java.util.Locale.ROOT, "%.2f", gamma.getValue() / 100.0));
    }

    private void slidersChanged() {
        updateValues();
        if (updatingControls || committed == null) return;
        // Debounce не запускает дорогой пересчёт на каждый отдельный пиксель движения мыши.
        previewPending = true;
        debounce.restart();
        updateActions();
    }

    private void selectRegion(Rectangle roi) {
        if (exclusive || committed == null) return;
        selection = roi;
        originalView.setSelection(roi);
        resultView.setSelection(roi);
        selectionLabel.setText(roi == null ? "Область: всё изображение"
                : String.format("x=%d, y=%d · %d × %d px", roi.x, roi.y, roi.width, roi.height));
        debounce.stop();
        preview();
    }

    private void preview() {
        if (committed == null || exclusive) return;
        debounce.stop();
        int delta = brightness.getValue();
        double factor = contrast.getValue() / 100.0, g = gamma.getValue() / 100.0;
        Rectangle roi = selectionSnapshot();
        render(hasAdjustments() ? "Предпросмотр коррекции" : "Готово", committed,
                im -> delta == 0 && factor == 1 && g == 1 ? im
                        : ImageProcessor.adjust(im, roi, delta, factor, g), false);
    }

    /**
     * Работник всегда получает неизменяемый снимок входа и параметров.
     * Номер revision защищает от гонки: медленный старый предпросмотр не может
     * перезаписать новый результат. cancel(true) дополнительно прерывает его циклы.
     */
    private void render(String label, BufferedImage input,
                        Function<BufferedImage, BufferedImage> transform, boolean commit) {
        render(label, input, transform, commit, false);
    }

    private void render(String label, BufferedImage input,
                        Function<BufferedImage, BufferedImage> transform, boolean commit,
                        boolean binarization) {
        if (worker != null) worker.cancel(true);
        long current = ++revision;
        Rectangle roi = selectionSnapshot();
        BufferedImage sourceOriginal = original;
        busy = true;
        exclusive = commit;
        previewPending = false;
        status.setText(label + "…");
        updateActions();
        worker = new SwingWorker<>() {
            @Override protected Render doInBackground() {
                BufferedImage image = transform.apply(input);
                String detail = label;
                if (label.startsWith("Оцу")) {
                    detail += ": T=" + ImageProcessor.otsuThreshold(ImageProcessor.histogram(input, roi));
                }
                return new Render(image, ImageProcessor.histogram(sourceOriginal, roi),
                        ImageProcessor.histogram(image, roi), detail);
            }

            @Override protected void done() {
                if (current != revision) return;
                busy = false;
                exclusive = false;
                try {
                    Render result = get();
                    if (commit) {
                        pushHistory(committed);
                        committed = result.image;
                        // Меняем снимок только после успешного вычисления. Обычное
                        // применение другой операции завершает возможность отдельного
                        // сброса бинаризации; прежние состояния доступны через «Отменить».
                        beforeBinarization = binarization ? input : null;
                        neutralControls();
                    }
                    displayed = result.image;
                    resultView.setImage(displayed);
                    histogram.setHistograms(result.before, result.after);
                    updateStats(result.after);
                    status.setText(result.detail + " · " + displayed.getWidth() + " × " + displayed.getHeight()
                            + " px" + (hasAdjustments() ? " · предпросмотр: нажмите «Применить коррекцию»" : ""));
                } catch (CancellationException ignored) {
                    status.setText("Обработка отменена");
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException ex) {
                    showError("Обработка не выполнена: " + ex.getCause().getMessage());
                }
                updateActions();
            }
        };
        worker.execute();
    }

    private void updateStats(long[] h) {
        long count = 0;
        double sum = 0, sumSq = 0;
        int min = -1, max = 0;
        for (int i = 0; i < 256; i++) {
            count += h[i]; sum += (double) i * h[i]; sumSq += (double) i * i * h[i];
            if (h[i] != 0) { if (min < 0) min = i; max = i; }
        }
        double mean = count == 0 ? 0 : sum / count;
        double std = count == 0 ? 0 : Math.sqrt(Math.max(0, sumSq / count - mean * mean));
        histogramStats.setText(String.format("%s · Пикселей: %,d · min: %d · max: %d · Среднее: %.2f · σ: %.2f",
                selection == null ? "Всё изображение" : "Выделенный фрагмент", count, min, max, mean, std));
    }

    private void pushHistory(BufferedImage image) {
        // Метаданные сброса хранятся вместе с изображением: Ctrl+Z после сброса
        // возвращает бинарный результат и снова включает кнопку его сброса.
        history.push(new HistoryState(image, beforeBinarization));
        historyBytes += (long) image.getWidth() * image.getHeight() * 4;
        // История ограничена и количеством, и памятью; самое свежее состояние сохраняем.
        while (history.size() > 1 && (history.size() > 12 || historyBytes > HISTORY_LIMIT_BYTES)) {
            BufferedImage removed = history.removeLast().image;
            historyBytes -= (long) removed.getWidth() * removed.getHeight() * 4;
        }
    }

    private void undo() {
        if (!ready()) return;
        if (hasAdjustments()) {
            neutralControls();
            preview();
        } else if (!history.isEmpty()) {
            HistoryState restored = history.pop();
            committed = restored.image;
            beforeBinarization = restored.beforeBinarization;
            historyBytes -= (long) committed.getWidth() * committed.getHeight() * 4;
            preview();
        }
    }

    private void updateActions() {
        boolean ready = ready();
        open.setEnabled(!busy && !previewPending);
        save.setEnabled(ready);
        undo.setEnabled(ready && (hasAdjustments() || !history.isEmpty()));
        reset.setEnabled(ready);
        apply.setEnabled(ready && hasAdjustments());
        cancelPreview.setEnabled(committed != null && !exclusive && hasAdjustments());
        clearSelection.setEnabled(committed != null && !exclusive && selection != null);
        resetBinarization.setEnabled(ready && beforeBinarization != null);
        resultView.setEnabled(committed != null && !exclusive);
        for (JButton button : operationButtons) button.setEnabled(ready);
        for (JComponent control : new JComponent[]{brightness, contrast, gamma, window, bradleyPercent, wolfK}) {
            control.setEnabled(committed != null && !exclusive);
        }
    }

    private void chooseOpen() {
        if (busy || previewPending) return;
        JFileChooser chooser = new JFileChooser(openedFile == null ? new File(".") : openedFile.getParentFile());
        chooser.setDialogTitle("Открыть фотоизображение");
        chooser.setFileFilter(new FileNameExtensionFilter("Изображения (JPG, PNG, BMP, GIF)",
                "jpg", "jpeg", "png", "bmp", "gif"));
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) load(chooser.getSelectedFile());
    }

    /** Декодирование также вынесено из EDT; ImageIO.read возвращает null для неизвестного формата. */
    public void load(File file) {
        if (busy || previewPending) return;
        busy = exclusive = true;
        updateActions();
        status.setText("Открытие " + file.getName() + "…");
        new SwingWorker<BufferedImage, Void>() {
            @Override protected BufferedImage doInBackground() throws IOException {
                BufferedImage decoded = ImageIO.read(file);
                if (decoded == null) throw new IOException("Формат файла не поддерживается.");
                return ImageProcessor.copy(decoded);
            }

            @Override protected void done() {
                busy = exclusive = false;
                try {
                    original = committed = displayed = get();
                    beforeBinarization = null;
                    openedFile = file.getAbsoluteFile();
                    history.clear(); historyBytes = 0;
                    neutralControls();
                    originalView.setImage(original);
                    resultView.setImage(displayed);
                    setTitle("ЛР 5 · " + file.getName() + " · Обработка фотоизображений");
                    selectRegion(null);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException ex) {
                    showError("Не удалось открыть изображение: " + ex.getCause().getMessage());
                }
                updateActions();
            }
        }.execute();
    }

    private void chooseSave() {
        if (!ready()) return;
        JFileChooser chooser = new JFileChooser(openedFile == null ? new File(".") : openedFile.getParentFile());
        chooser.setDialogTitle("Сохранить результат");
        FileNameExtensionFilter png = new FileNameExtensionFilter("PNG — без потерь", "png");
        FileNameExtensionFilter jpg = new FileNameExtensionFilter("JPEG — с потерями", "jpg", "jpeg");
        chooser.setAcceptAllFileFilterUsed(false);
        chooser.addChoosableFileFilter(png); chooser.addChoosableFileFilter(jpg);
        chooser.setFileFilter(png);
        chooser.setSelectedFile(new File("result.png"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File file = chooser.getSelectedFile();
        String lower = file.getName().toLowerCase(java.util.Locale.ROOT);
        String format;
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) format = "jpg";
        else if (lower.endsWith(".png")) format = "png";
        else {
            format = chooser.getFileFilter() == jpg ? "jpg" : "png";
            file = new File(file.getParentFile(), file.getName() + "." + format);
        }
        if (file.exists() && JOptionPane.showConfirmDialog(this, "Заменить файл «" + file.getName() + "»?",
                "Файл уже существует", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        File destination = file;
        BufferedImage snapshot = displayed;
        busy = exclusive = true;
        updateActions();
        status.setText("Сохранение…");
        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() throws IOException {
                BufferedImage output = snapshot;
                if (format.equals("jpg")) {
                    // JPEG не поддерживает альфа-канал; прозрачность накладываем на белый фон.
                    output = new BufferedImage(snapshot.getWidth(), snapshot.getHeight(), BufferedImage.TYPE_INT_RGB);
                    Graphics2D g = output.createGraphics();
                    g.setColor(Color.WHITE); g.fillRect(0, 0, output.getWidth(), output.getHeight());
                    g.drawImage(snapshot, 0, 0, null); g.dispose();
                }
                if (!ImageIO.write(output, format, destination)) throw new IOException("Не найден кодировщик " + format);
                return null;
            }
            @Override protected void done() {
                busy = exclusive = false;
                try {
                    get();
                    status.setText("Сохранено: " + destination.getAbsolutePath());
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException ex) {
                    showError("Не удалось сохранить файл: " + ex.getCause().getMessage());
                }
                updateActions();
            }
        }.execute();
    }

    private void showError(String message) {
        status.setText(message);
        JOptionPane.showMessageDialog(this, message, "Ошибка", JOptionPane.ERROR_MESSAGE);
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel("javax.swing.plaf.nimbus.NimbusLookAndFeel");
            } catch (Exception ignored) { /* Системный Look and Feel остаётся запасным вариантом. */ }
            UIManager.put("defaultFont", new Font(Font.SANS_SERIF, Font.PLAIN, 13));
            PhotoEditor app = new PhotoEditor();
            app.setVisible(true);
            File image = args.length > 0 ? new File(args[0]) : new File("picture.jpg");
            if (!image.isFile() && args.length == 0) {
                // Двойной щелчок по JAR может установить другую рабочую директорию.
                try {
                    Path location = Path.of(PhotoEditor.class.getProtectionDomain().getCodeSource().getLocation().toURI());
                    image = location.getParent().resolve("picture.jpg").toFile();
                } catch (Exception ignored) { /* Пользователь сможет открыть файл кнопкой. */ }
            }
            if (image.isFile()) app.load(image);
        });
    }
}
