package ru.graphics.lab5;

import javax.imageio.ImageIO;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.function.Function;

/**
 * Самодостаточные тесты без JUnit и скачивания зависимостей.
 * Главное — сравнение быстрых локальных алгоритмов с независимым медленным
 * эталоном, который обходит каждый пиксель окна напрямую, без интегральных сумм.
 */
public final class ImageProcessorTest {
    private static int checks;

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static BufferedImage image(int width, int... levels) {
        BufferedImage result = new BufferedImage(width, levels.length / width, BufferedImage.TYPE_INT_ARGB);
        for (int i = 0; i < levels.length; i++) {
            int c = levels[i];
            result.setRGB(i % width, i / width, 0xff000000 | c << 16 | c << 8 | c);
        }
        return result;
    }

    private static void same(BufferedImage expected, BufferedImage actual, String name) {
        check(expected.getWidth() == actual.getWidth() && expected.getHeight() == actual.getHeight(), name + " size");
        for (int y = 0; y < expected.getHeight(); y++) {
            for (int x = 0; x < expected.getWidth(); x++) {
                check(expected.getRGB(x, y) == actual.getRGB(x, y), name + " at " + x + "," + y);
            }
        }
    }

    private static void binary(BufferedImage actual, Rectangle roi, String name) {
        Rectangle r = ImageProcessor.region(actual, roi);
        for (int y = r.y; y < r.y + r.height; y++) {
            for (int x = r.x; x < r.x + r.width; x++) {
                int c = actual.getRGB(x, y) & 0xffffff;
                check(c == 0 || c == 0xffffff, name + " must have only black/white pixels");
            }
        }
    }

    /** Независимая реализация локальных статистик прямым обходом окна. */
    private static BufferedImage slowLocal(BufferedImage source, Rectangle roi, int window,
                                           boolean wolf, double parameter) {
        Rectangle r = ImageProcessor.region(source, roi);
        double[][] means = new double[r.height][r.width];
        double[][] deviations = new double[r.height][r.width];
        double maxStd = 0;
        int minimum = 255;
        for (int y = 0; y < r.height; y++) {
            for (int x = 0; x < r.width; x++) {
                minimum = Math.min(minimum, ImageProcessor.luminance(source.getRGB(r.x + x, r.y + y)));
                double sum = 0, squares = 0;
                int count = 0;
                for (int yy = Math.max(0, y - window / 2); yy <= Math.min(r.height - 1, y + window / 2); yy++) {
                    for (int xx = Math.max(0, x - window / 2); xx <= Math.min(r.width - 1, x + window / 2); xx++) {
                        int value = ImageProcessor.luminance(source.getRGB(r.x + xx, r.y + yy));
                        sum += value; squares += value * value; count++;
                    }
                }
                double mean = sum / count;
                means[y][x] = mean;
                deviations[y][x] = Math.sqrt(Math.max(0, squares / count - mean * mean));
                maxStd = Math.max(maxStd, deviations[y][x]);
            }
        }
        BufferedImage output = ImageProcessor.copy(source);
        for (int y = 0; y < r.height; y++) {
            for (int x = 0; x < r.width; x++) {
                double m = means[y][x];
                double threshold = wolf ? (maxStd < 1e-12 ? 127
                        : (1 - parameter) * m + parameter * minimum
                        + parameter * (deviations[y][x] / maxStd) * (m - minimum))
                        : m * (1 - parameter);
                int color = source.getRGB(r.x + x, r.y + y);
                int rgb = ImageProcessor.luminance(color) < threshold ? 0 : 0xffffff;
                output.setRGB(r.x + x, r.y + y, (color & 0xff000000) | rgb);
            }
        }
        return output;
    }

    /** Другой критерий Оцу: минимизация суммы внутриклассовых дисперсий. */
    private static int referenceOtsu(long[] h) {
        int best = 0;
        double bestScore = Double.POSITIVE_INFINITY;
        for (int t = 0; t < 255; t++) {
            double score = 0;
            boolean valid = true;
            for (int group = 0; group < 2; group++) {
                int start = group == 0 ? 0 : t + 1, end = group == 0 ? t : 255;
                long count = 0;
                double sum = 0;
                for (int i = start; i <= end; i++) { count += h[i]; sum += (double) i * h[i]; }
                if (count == 0) { valid = false; break; }
                double mean = sum / count;
                for (int i = start; i <= end; i++) score += h[i] * (i - mean) * (i - mean);
            }
            if (valid && score < bestScore - 1e-9) { bestScore = score; best = t; }
        }
        return best;
    }

    private static void expectInvalid(Runnable action, String name) {
        boolean caught = false;
        try { action.run(); } catch (IllegalArgumentException ex) { caught = true; }
        check(caught, name);
    }

    public static void main(String[] args) throws Exception {
        check(ImageProcessor.luminance(0xff000000) == 0, "black luminance");
        check(ImageProcessor.luminance(0xffffffff) == 255, "white luminance");
        check(ImageProcessor.luminance(0xffff0000) == 76, "red weight");
        check(ImageProcessor.luminance(0xff00ff00) == 150, "green weight");
        check(ImageProcessor.luminance(0xff0000ff) == 29, "blue weight");

        BufferedImage values = image(3, 0, 50, 100, 150, 200, 255);
        same(values, ImageProcessor.adjust(values, null, 0, 1, 1), "neutral correction");
        same(values, ImageProcessor.negative(ImageProcessor.negative(values, null), null), "double negative");
        same(values, ImageProcessor.grayscale(values, null), "gray idempotent");
        check(Arrays.stream(ImageProcessor.histogram(values, null)).sum() == 6, "histogram area");
        for (int delta : new int[]{-255, 255}) {
            BufferedImage adjusted = ImageProcessor.adjust(values, null, delta, 3, 0.2);
            for (int y = 0; y < 2; y++) for (int x = 0; x < 3; x++) {
                check((adjusted.getRGB(x, y) & 0xffffff) == (delta < 0 ? 0 : 0xffffff), "brightness extremes");
            }
        }
        check(ImageProcessor.luminance(ImageProcessor.adjust(image(1, 250), null, 30, 1, 1).getRGB(0, 0)) == 255,
                "brightness clips rather than wraps");
        BufferedImage contrasted = ImageProcessor.adjust(image(3, 50, 100, 150), null, 0, 2, 1);
        same(image(3, 0, 100, 200), contrasted, "contrast about mean");
        same(image(3, 100, 100, 100), ImageProcessor.adjust(image(3, 50, 100, 150), null, 0, 0, 1), "zero contrast");
        check(ImageProcessor.luminance(ImageProcessor.adjust(image(1, 64), null, 0, 1, 0.5).getRGB(0, 0)) == 128,
                "gamma correction");

        BufferedImage clusters = image(4, 10, 10, 20, 20, 210, 210, 220, 220);
        check(ImageProcessor.otsuThreshold(ImageProcessor.histogram(clusters, null)) == 20, "Otsu two clusters");
        BufferedImage separated = ImageProcessor.otsu(clusters, null);
        check((separated.getRGB(3, 0) & 0xffffff) == 0, "Otsu includes threshold in dark class");
        check((separated.getRGB(0, 1) & 0xffffff) == 0xffffff, "Otsu light class");
        same(image(3, 0, 128, 255), ImageProcessor.stretch(image(3, 50, 100, 150), null), "stretch formula");
        same(image(4, 0, 0, 128, 255), ImageProcessor.equalize(image(4, 50, 50, 100, 200), null), "equalization CDF");

        Random random = new Random(2026);
        BufferedImage colors = new BufferedImage(13, 11, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < colors.getHeight(); y++) for (int x = 0; x < colors.getWidth(); x++) {
            colors.setRGB(x, y, random.nextInt());
        }
        BufferedImage originalCopy = ImageProcessor.copy(colors);
        Rectangle roi = new Rectangle(3, 2, 7, 6);
        List<Function<BufferedImage, BufferedImage>> operations = List.of(
                im -> ImageProcessor.adjust(im, roi, 50, 1.5, 0.8),
                im -> ImageProcessor.grayscale(im, roi), im -> ImageProcessor.negative(im, roi),
                im -> ImageProcessor.otsu(im, roi), im -> ImageProcessor.bradley(im, roi, 5, 0.15),
                im -> ImageProcessor.wolf(im, roi, 5, 0.5), im -> ImageProcessor.stretch(im, roi),
                im -> ImageProcessor.equalize(im, roi));
        for (Function<BufferedImage, BufferedImage> operation : operations) {
            BufferedImage result = operation.apply(colors);
            for (int y = 0; y < colors.getHeight(); y++) for (int x = 0; x < colors.getWidth(); x++) {
                if (!roi.contains(x, y)) check(result.getRGB(x, y) == colors.getRGB(x, y), "ROI isolation");
                check((result.getRGB(x, y) & 0xff000000) == (colors.getRGB(x, y) & 0xff000000), "alpha preserved");
            }
        }
        same(originalCopy, colors, "source not mutated");
        check(Arrays.stream(ImageProcessor.histogram(colors, roi)).sum() == (long) roi.width * roi.height, "ROI histogram");

        for (Rectangle area : new Rectangle[]{null, roi, new Rectangle(12, 10, 1, 1), new Rectangle(0, 0, 1, 11)}) {
            for (int size : new int[]{3, 5, 31}) {
                same(slowLocal(colors, area, size, false, 0.15), ImageProcessor.bradley(colors, area, size, 0.15), "Bradley reference");
                same(slowLocal(colors, area, size, true, 0.5), ImageProcessor.wolf(colors, area, size, 0.5), "Wolf reference");
                binary(ImageProcessor.bradley(colors, area, size, 0.15), area, "Bradley");
                binary(ImageProcessor.wolf(colors, area, size, 0.5), area, "Wolf");
            }
        }
        for (int run = 0; run < 30; run++) {
            long[] h = new long[256];
            for (int i = 0; i < h.length; i++) h[i] = random.nextInt(50);
            check(ImageProcessor.otsuThreshold(h) == referenceOtsu(h), "Otsu independent variance criterion");
        }
        for (int level : new int[]{0, 100, 255}) {
            BufferedImage flat = image(2, level, level, level, level);
            same(flat, ImageProcessor.equalize(flat, null), "constant equalization");
            same(flat, ImageProcessor.stretch(flat, null), "constant stretch");
            binary(ImageProcessor.otsu(flat, null), null, "constant Otsu");
            binary(ImageProcessor.wolf(flat, null, 31, 0.5), null, "constant Wolf R=0");
            binary(ImageProcessor.bradley(flat, null, 31, 0.15), null, "constant Bradley");
        }
        expectInvalid(() -> ImageProcessor.bradley(colors, null, 4, 0.15), "even window rejected");
        expectInvalid(() -> ImageProcessor.wolf(colors, null, 3, Double.NaN), "NaN rejected");
        expectInvalid(() -> ImageProcessor.adjust(colors, null, 0, 1, 0), "zero gamma rejected");
        expectInvalid(() -> ImageProcessor.histogram(colors, new Rectangle(100, 100, 1, 1)), "empty ROI rejected");
        Rectangle clipped = new Rectangle(-3, -2, 5, 4);
        check(Arrays.stream(ImageProcessor.histogram(colors, clipped)).sum() == 4, "ROI clipped to bounds");

        // Интеграционная проверка на фотографии из задания, включая сохранение PNG.
        BufferedImage photo = ImageIO.read(new File("picture.jpg"));
        check(photo != null, "task photo decoded");
        check(Arrays.stream(ImageProcessor.histogram(photo, null)).sum() == (long) photo.getWidth() * photo.getHeight(), "photo histogram");
        Rectangle photoRoi = new Rectangle(20, 30, 180, 150);
        binary(ImageProcessor.otsu(photo, null), null, "photo Otsu");
        binary(ImageProcessor.bradley(photo, null, 31, 0.15), null, "photo Bradley");
        BufferedImage photoWolf = ImageProcessor.wolf(photo, photoRoi, 31, 0.5);
        binary(photoWolf, photoRoi, "photo Wolf ROI");
        File output = new File("tmp/test-output/roundtrip.png");
        output.getParentFile().mkdirs();
        check(ImageIO.write(photoWolf, "png", output), "PNG encoder");
        same(photoWolf, ImageIO.read(output), "PNG roundtrip");
        System.out.println("PASS: " + checks + " checks; algorithms, reference comparison, ROI, edge cases, photo and PNG roundtrip.");
    }
}
