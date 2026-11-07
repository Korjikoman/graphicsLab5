package ru.graphics.lab5;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.concurrent.CancellationException;

/**
 * Алгоритмы обработки изображений. 
 */
public final class ImageProcessor {
    private ImageProcessor() { }

    /** Взвешенная яркость BT.601, округлённая до ближайшего целого. */
    public static int luminance(int rgb) {
        int r = (rgb >>> 16) & 255;
        int g = (rgb >>> 8) & 255;
        int b = rgb & 255;
        // Целочисленные коэффициенты суммируются в 1000: белый остаётся 255.
        return (299 * r + 587 * g + 114 * b + 500) / 1000;
    }

    public static int clamp(double value) {
        return (int) Math.max(0, Math.min(255, Math.round(value)));
    }

    public static Rectangle region(BufferedImage image, Rectangle roi) {
        Rectangle bounds = new Rectangle(0, 0, image.getWidth(), image.getHeight());
        Rectangle result = roi == null ? bounds : bounds.intersection(roi);
        if (result.isEmpty()) {
            throw new IllegalArgumentException("Выделенная область не пересекает изображение.");
        }
        return result;
    }

    public static BufferedImage copy(BufferedImage image) {
        BufferedImage result = new BufferedImage(image.getWidth(), image.getHeight(),
                BufferedImage.TYPE_INT_ARGB);
        int[] pixels = image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
        result.setRGB(0, 0, image.getWidth(), image.getHeight(), pixels, 0, image.getWidth());
        return result;
    }

    private static void checkCancelled() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Обработка отменена.");
        }
    }

    @FunctionalInterface
    private interface PixelTransform {
        int apply(int argb);
    }

    private static BufferedImage map(BufferedImage source, Rectangle roi, PixelTransform transform) {
        Rectangle r = region(source, roi);
        BufferedImage result = copy(source);
        for (int y = r.y; y < r.y + r.height; y++) {
            checkCancelled();
            for (int x = r.x; x < r.x + r.width; x++) {
                int color = source.getRGB(x, y);
                // Альфа-канал не является цветом и не должен инвертироваться или осветляться.
                result.setRGB(x, y, (color & 0xff000000) | (transform.apply(color) & 0xffffff));
            }
        }
        return result;
    }

    private static int grayRgb(int value) {
        return (value << 16) | (value << 8) | value;
    }

    /** h[i] — количество пикселей яркости i. Сумма h равна площади ROI. */
    public static long[] histogram(BufferedImage source, Rectangle roi) {
        Rectangle r = region(source, roi);
        long[] h = new long[256];
        for (int y = r.y; y < r.y + r.height; y++) {
            checkCancelled();
            for (int x = r.x; x < r.x + r.width; x++) {
                h[luminance(source.getRGB(x, y))]++;
            }
        }
        return h;
    }

    /**
     * Комбинированная коррекция для интерактивного предпросмотра.
     * Порядок: контрастность -> яркость -> гамма, с ограничением после каждого этапа.
     * C1 = K * (C - mean(C)) + mean(C), C2 = clip(C1 + delta),
     * C3 = 255 * (C2 / 255)^gamma. Среднее вычисляется по исходному ROI
     * отдельно для каждого канала. При K=1, delta=0, gamma=1 изменений нет.
     * delta=-255 гарантирует чёрный, delta=255 — белый при любом K.
     * Среднее при изменении K сохраняется ДО округления и ограничения диапазона.
     */
    public static BufferedImage adjust(BufferedImage source, Rectangle roi,
                                       int delta, double contrast, double gamma) {
        if (delta < -255 || delta > 255 || !Double.isFinite(contrast) || contrast < 0
                || !Double.isFinite(gamma) || gamma <= 0) {
            throw new IllegalArgumentException("Некорректные параметры коррекции.");
        }
        Rectangle r = region(source, roi);
        if (delta == 0 && contrast == 1 && gamma == 1) {
            return copy(source);
        }
        long[] sums = new long[3];
        if (contrast != 1) {
            for (int y = r.y; y < r.y + r.height; y++) {
                checkCancelled();
                for (int x = r.x; x < r.x + r.width; x++) {
                    int c = source.getRGB(x, y);
                    sums[0] += (c >>> 16) & 255;
                    sums[1] += (c >>> 8) & 255;
                    sums[2] += c & 255;
                }
            }
        }
        double count = (double) r.width * r.height;
        int[][] lut = new int[3][256];
        for (int channel = 0; channel < 3; channel++) {
            double mean = sums[channel] / count;
            for (int c = 0; c < 256; c++) {
                int contrasted = clamp(contrast * (c - mean) + mean);
                int brightened = clamp(contrasted + delta);
                lut[channel][c] = clamp(255 * Math.pow(brightened / 255.0, gamma));
            }
        }
        // LUT — таблица замен: степень и остальные вычисления делаем 256 раз,
        // а не заново для каждого из миллионов пикселей.
        return map(source, r, c -> (lut[0][(c >>> 16) & 255] << 16)
                | (lut[1][(c >>> 8) & 255] << 8) | lut[2][c & 255]);
    }

    public static BufferedImage grayscale(BufferedImage source, Rectangle roi) {
        return map(source, roi, c -> grayRgb(luminance(c)));
    }

    public static BufferedImage negative(BufferedImage source, Rectangle roi) {
        // XOR с 0xffffff эквивалентен R'=255-R, G'=255-G, B'=255-B.
        return map(source, roi, c -> c ^ 0x00ffffff);
    }

    /**
     * Метод Оцу ищет один глобальный порог, максимально разделяющий два класса.
     * Для каждого t: класс 0 содержит [0..t], класс 1 — [t+1..255].
     * Максимизируем w0*w1*(mean0-mean1)^2. Нормировать веса на N не нужно:
     * постоянный множитель не изменяет положение максимума.
     */
    public static int otsuThreshold(long[] histogram) {
        if (histogram.length != 256) throw new IllegalArgumentException("Нужно 256 столбцов.");
        long total = 0;
        double sum = 0;
        for (int i = 0; i < 256; i++) {
            total += histogram[i];
            sum += (double) i * histogram[i];
        }
        if (total == 0) throw new IllegalArgumentException("Пустая гистограмма.");
        long weight0 = 0;
        double sum0 = 0;
        double bestVariance = -1;
        int threshold = 0;
        for (int t = 0; t < 256; t++) {
            weight0 += histogram[t];
            sum0 += (double) t * histogram[t];
            long weight1 = total - weight0;
            if (weight0 == 0) continue;
            if (weight1 == 0) break;
            double diff = sum0 / weight0 - (sum - sum0) / weight1;
            double variance = (double) weight0 * weight1 * diff * diff;
            if (variance > bestVariance) {
                bestVariance = variance;
                threshold = t;
            }
        }
        // У однотонной картинки два непустых класса невозможны. Явно задаём
        // предсказуемый запасной порог: тёмные поля чёрные, светлые белые.
        return bestVariance < 0 ? 127 : threshold;
    }

    public static BufferedImage otsu(BufferedImage source, Rectangle roi) {
        int threshold = otsuThreshold(histogram(source, roi));
        return map(source, roi, c -> luminance(c) <= threshold ? 0 : 0xffffff);
    }

    private static void validateWindow(int window) {
        if (window < 3 || window % 2 == 0) {
            throw new IllegalArgumentException("Окно должно быть нечётным и не меньше 3.");
        }
    }

    /**
     * Метод Брэдли–Рота: пиксель чёрный, если Y < localMean*(1-t).
     * t задаётся в долях (0.15 = 15%). Окрестность у границ обрезается по ROI.
     * Интегральное изображение даёт сумму окна за O(1), поэтому весь алгоритм
     * работает за O(W*H), независимо от выбранного размера окна.
     */
    public static BufferedImage bradley(BufferedImage source, Rectangle roi, int window, double t) {
        validateWindow(window);
        if (!Double.isFinite(t) || t < 0 || t >= 1) {
            throw new IllegalArgumentException("Параметр Брэдли должен быть от 0 до 1 (не включая 1).");
        }
        Rectangle r = region(source, roi);
        Integral integral = new Integral(source, r, false);
        BufferedImage result = copy(source);
        int radius = window / 2;
        for (int y = 0; y < r.height; y++) {
            checkCancelled();
            for (int x = 0; x < r.width; x++) {
                Stats stats = integral.stats(x, y, radius);
                int argb = source.getRGB(r.x + x, r.y + y);
                int value = luminance(argb) < stats.mean * (1 - t) ? 0 : 255;
                result.setRGB(r.x + x, r.y + y, (argb & 0xff000000) | grayRgb(value));
            }
        }
        return result;
    }

    /**
     * Метод Кристиана Вольфа (Wolf–Jolion).
     * T(x,y) = m + k*(s/R - 1)*(m-M), где m и s — локальные среднее и
     * стандартное отклонение, M — минимальная яркость ROI, R — максимальное
     * локальное стандартное отклонение по ROI. Сначала находим R, затем пороги.
     * Нужны интегральные суммы Y и Y^2: variance=E[Y^2]-E[Y]^2.
     * На постоянном изображении R=0: деление невозможно, используем порог 127.
     */
    public static BufferedImage wolf(BufferedImage source, Rectangle roi, int window, double k) {
        validateWindow(window);
        if (!Double.isFinite(k) || k < 0 || k > 1) {
            throw new IllegalArgumentException("Коэффициент Вольфа должен быть от 0 до 1.");
        }
        Rectangle r = region(source, roi);
        Integral integral = new Integral(source, r, true);
        int radius = window / 2;
        double maxStd = 0;
        for (int y = 0; y < r.height; y++) {
            checkCancelled();
            for (int x = 0; x < r.width; x++) {
                maxStd = Math.max(maxStd, integral.stats(x, y, radius).std);
            }
        }
        BufferedImage result = copy(source);
        for (int y = 0; y < r.height; y++) {
            checkCancelled();
            for (int x = 0; x < r.width; x++) {
                Stats stats = integral.stats(x, y, radius);
                double threshold = maxStd < 1e-12 ? 127
                        : stats.mean + k * (stats.std / maxStd - 1) * (stats.mean - integral.minimum);
                int argb = source.getRGB(r.x + x, r.y + y);
                int value = luminance(argb) < threshold ? 0 : 255;
                result.setRGB(r.x + x, r.y + y, (argb & 0xff000000) | grayRgb(value));
            }
        }
        return result;
    }

    /**
     * Растяжение гистограммы: занятый диапазон [minY,maxY] отображаем в [0,255].
     * Это глобальное преобразование яркости, а не независимая коррекция R/G/B.
     * Однотонное изображение оставляем как есть: растягивать нечего.
     */
    public static BufferedImage stretch(BufferedImage source, Rectangle roi) {
        long[] h = histogram(source, roi);
        int min = 0, max = 255;
        while (h[min] == 0) min++;
        while (h[max] == 0) max--;
        int[] lut = new int[256];
        for (int i = 0; i < 256; i++) {
            lut[i] = max == min ? i : clamp((i - min) * 255.0 / (max - min));
        }
        return applyLuminanceLut(source, roi, lut);
    }

    /**
     * Эквализация: строим CDF — накопленное количество пикселей до яркости y.
     * LUT[y] = round(255*(CDF[y]-CDFmin)/(N-CDFmin)). Вычитание CDFmin
     * позволяет самому тёмному занятому уровню перейти в 0. Результат стремится
     * использовать весь диапазон, но дискретная гистограмма не обязана стать ровной.
     * При единственном уровне знаменатель нулевой: возвращаем исходные цвета.
     */
    public static BufferedImage equalize(BufferedImage source, Rectangle roi) {
        long[] h = histogram(source, roi);
        long total = Arrays.stream(h).sum();
        long first = 0;
        for (long count : h) if (count != 0) { first = count; break; }
        if (total == first) return copy(source);
        int[] lut = new int[256];
        long cdf = 0;
        for (int i = 0; i < 256; i++) {
            cdf += h[i];
            lut[i] = clamp(255.0 * (cdf - first) / (total - first));
        }
        return applyLuminanceLut(source, roi, lut);
    }

    /**
     * Возвращаем новую яркость в цветное изображение общей добавкой dY к R/G/B.
     * До ограничения диапазона сохраняются межканальные разности (цветность).
     * В насыщенных цветах ограничение может изменить цветность: это нормальное
     * ограничение простого 8-битного RGB.
     * Крайние уровни явно превращаем в чёрный/белый, чтобы растяжение доходило
     * до концов диапазона даже у насыщенных цветных пикселей.
     */
    private static BufferedImage applyLuminanceLut(BufferedImage source, Rectangle roi, int[] lut) {
        return map(source, roi, c -> {
            int y = luminance(c);
            int target = lut[y];
            if (target == y) return c;
            if (target == 0 || target == 255) return grayRgb(target);
            int delta = target - y;
            return (clamp(((c >>> 16) & 255) + delta) << 16)
                    | (clamp(((c >>> 8) & 255) + delta) << 8) | clamp((c & 255) + delta);
        });
    }

    private record Stats(double mean, double std) { }

    /**
     * Нулевая строка/колонка упрощают края. S(x,y) содержит сумму прямоугольника
     * [0,x) x [0,y). Сумма окна = S(right,bottom)-S(left,bottom)
     * -S(right,top)+S(left,top). Для квадратов используем long: int быстро
     * переполнится, ведь даже один белый пиксель даёт 255^2=65025.
     */
    private static final class Integral {
        final int width, height, stride;
        final long[] sum, square;
        int minimum = 255;

        Integral(BufferedImage source, Rectangle r, boolean withSquares) {
            width = r.width;
            height = r.height;
            stride = width + 1;
            int length = Math.toIntExact((long) stride * (height + 1));
            sum = new long[length];
            square = withSquares ? new long[length] : null;
            for (int y = 1; y <= height; y++) {
                checkCancelled();
                long rowSum = 0, rowSquare = 0;
                for (int x = 1; x <= width; x++) {
                    int value = luminance(source.getRGB(r.x + x - 1, r.y + y - 1));
                    minimum = Math.min(minimum, value);
                    rowSum += value;
                    sum[y * stride + x] = sum[(y - 1) * stride + x] + rowSum;
                    if (withSquares) {
                        rowSquare += (long) value * value;
                        square[y * stride + x] = square[(y - 1) * stride + x] + rowSquare;
                    }
                }
            }
        }

        long box(long[] data, int left, int top, int right, int bottom) {
            return data[bottom * stride + right] - data[bottom * stride + left]
                    - data[top * stride + right] + data[top * stride + left];
        }

        Stats stats(int x, int y, int radius) {
            int left = Math.max(0, x - radius), right = Math.min(width, x + radius + 1);
            int top = Math.max(0, y - radius), bottom = Math.min(height, y + radius + 1);
            double count = (double) (right - left) * (bottom - top);
            double mean = box(sum, left, top, right, bottom) / count;
            double variance = square == null ? 0
                    : box(square, left, top, right, bottom) / count - mean * mean;
            // Из-за погрешности double дисперсия константного окна может быть -epsilon.
            return new Stats(mean, Math.sqrt(Math.max(0, variance)));
        }
    }
}
