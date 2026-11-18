package com.feras.wazifati;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.text.Html;

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.text.PDFTextStripper;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** قراءة نص السيرة الذاتية من PDF أو Word (.docx) على الهاتف. */
public final class CvText {
    private CvText() { }

    public static String name(Context c, Uri u) {
        try (Cursor cur = c.getContentResolver().query(u, null, null, null, null)) {
            if (cur != null && cur.moveToFirst()) {
                int i = cur.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) return cur.getString(i);
            }
        } catch (Exception ignored) { }
        String p = u.getLastPathSegment();
        return p == null ? "cv" : p;
    }

    public static String extract(Context c, Uri u, String name) throws Exception {
        String type = c.getContentResolver().getType(u);
        String n = name == null ? "" : name.toLowerCase();
        byte[] data;
        try (InputStream in = c.getContentResolver().openInputStream(u)) {
            if (in == null) throw new Exception("تعذّر فتح الملف");
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[16384];
            int r;
            while ((r = in.read(b)) > 0) {
                bo.write(b, 0, r);
                if (bo.size() > 15_000_000) throw new Exception("الملف كبير جداً (أكثر من 15 ميغا)");
            }
            data = bo.toByteArray();
        }
        boolean pdf = n.endsWith(".pdf") || "application/pdf".equals(type) || startsWith(data, "%PDF");
        boolean zip = data.length > 4 && data[0] == 'P' && data[1] == 'K';
        if (pdf) return pdf(c, data);
        if (zip || n.endsWith(".docx")) return docx(data);
        if (n.endsWith(".doc") || "application/msword".equals(type))
            throw new Exception("صيغة Word القديمة (.doc) غير مدعومة. افتح الملف واحفظه بصيغة .docx أو PDF ثم أعد المحاولة.");
        if (n.endsWith(".txt") || (type != null && type.startsWith("text/"))) return new String(data, StandardCharsets.UTF_8);
        throw new Exception("اختر ملف PDF أو Word (.docx)");
    }

    private static boolean startsWith(byte[] d, String s) {
        if (d.length < s.length()) return false;
        for (int i = 0; i < s.length(); i++) if (d[i] != s.charAt(i)) return false;
        return true;
    }

    private static String pdf(Context c, byte[] data) throws Exception {
        PDFBoxResourceLoader.init(c.getApplicationContext());
        try (PDDocument doc = PDDocument.load(data)) {
            if (doc.isEncrypted()) throw new Exception("ملف PDF محمي بكلمة سر");
            PDFTextStripper st = new PDFTextStripper();
            st.setSortByPosition(true);
            return st.getText(doc);
        }
    }

    private static String docx(byte[] data) throws Exception {
        StringBuilder out = new StringBuilder();
        try (ZipInputStream z = new ZipInputStream(new java.io.ByteArrayInputStream(data))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                String en = e.getName();
                if (!(en.equals("word/document.xml") || en.startsWith("word/header") || en.startsWith("word/footer"))) continue;
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] b = new byte[16384];
                int r;
                while ((r = z.read(b)) > 0) bo.write(b, 0, r);
                String xml = new String(bo.toByteArray(), StandardCharsets.UTF_8)
                        .replaceAll("</w:p>", "\n")
                        .replaceAll("<w:tab/>|<w:br/>|<w:cr/>", " ")
                        .replaceAll("<[^>]+>", "");
                out.append(Html.fromHtml(xml.replace("\n", "<br>"), Html.FROM_HTML_MODE_LEGACY).toString()).append('\n');
            }
        }
        if (out.length() == 0) throw new Exception("الملف ليس Word صالحاً");
        return out.toString();
    }
}
