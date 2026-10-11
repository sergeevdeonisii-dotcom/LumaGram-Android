package org.telegram.messenger;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import javax.xml.parsers.DocumentBuilderFactory;

/** Desktop geometry/asset probe, not an Android screenshot or font/layout test. */
public final class ProfileVerificationVector105Test {
    private static int checks;
    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
    private static Path2D path(String data, int rule) {
        List<String> tokens = new ArrayList<>();
        Matcher matcher = Pattern.compile("[MLCZ]|-?\\d+(?:\\.\\d+)?").matcher(data);
        while (matcher.find()) tokens.add(matcher.group());
        Path2D result = new Path2D.Double(rule);
        int i = 0;
        char command = 0;
        while (i < tokens.size()) {
            String token = tokens.get(i);
            if (token.matches("[MLCZ]")) { command = token.charAt(0); i++; }
            if (command == 'Z') { result.closePath(); command = 0; continue; }
            int count = command == 'C' ? 6 : command == 'M' || command == 'L' ? 2 : 0;
            if (count == 0 || i + count > tokens.size()) throw new AssertionError("Unsupported vector command");
            double[] n = new double[count];
            for (int j = 0; j < count; j++) n[j] = Double.parseDouble(tokens.get(i++));
            if (command == 'M') { result.moveTo(n[0], n[1]); command = 'L'; }
            else if (command == 'L') result.lineTo(n[0], n[1]);
            else result.curveTo(n[0], n[1], n[2], n[3], n[4], n[5]);
        }
        return result;
    }
    public static void main(String[] args) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        org.w3c.dom.Document xml = factory.newDocumentBuilder().parse(new File(args[0]));
        String data = ((org.w3c.dom.Element) xml.getElementsByTagName("path").item(0))
                .getAttributeNS("http://schemas.android.com/apk/res/android", "pathData");
        Path2D evenOdd = path(data, Path2D.WIND_EVEN_ODD);
        Path2D nonzero = path(data, Path2D.WIND_NON_ZERO);
        int holeSamples = 0;
        Path2D outer = path(data.substring(0, data.indexOf('Z') + 1), Path2D.WIND_NON_ZERO);
        for (int x = 0; x < 72; x++) for (int y = 0; y < 72; y++) {
            check(evenOdd.contains(x + .5, y + .5) == nonzero.contains(x + .5, y + .5), "API 21-23 winding compatibility");
            if (outer.contains(x + .5, y + .5) && !evenOdd.contains(x + .5, y + .5)) holeSamples++;
        }
        check(holeSamples > 200 && holeSamples < 800, "visible bounded transparent plane cutout");
        check(evenOdd.getBounds2D().getMinX() >= 0 && evenOdd.getBounds2D().getMaxX() <= 72, "horizontal viewport bounds");
        check(evenOdd.getBounds2D().getMinY() >= 0 && evenOdd.getBounds2D().getMaxY() <= 72, "vertical viewport bounds");
        BufferedImage image = new BufferedImage(320, 160, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        for (int row = 0; row < 2; row++) {
            g.setColor(row == 0 ? new Color(0xfafafa) : new Color(0x202020));
            g.fillRect(0, row * 80, 320, 80);
            for (int n = 0; n < 3; n++) {
                int size = new int[] {17, 20, 68}[n];
                Graphics2D icon = (Graphics2D) g.create();
                icon.translate(30 + n * 95, row * 80 + (80 - size) / 2d);
                icon.scale(size / 72d, size / 72d);
                icon.setColor(n == 1 ? new Color(0x808080) : new Color(0x7188ea));
                icon.fill(evenOdd);
                icon.dispose();
            }
        }
        g.dispose();
        ImageIO.write(image, "png", new File(args[1]));
        System.out.println("PASS: " + checks + " vector geometry checks, including API 21-23 transparent cutout.");
    }
}
