package com.example.salesagent.rag;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

public final class PdfTestDocuments {
    private PdfTestDocuments() {}

    public static byte[] text(String... pages) throws IOException {
        return create(null, pages);
    }

    static byte[] encrypted(String password) throws IOException {
        return create(password, "Protected product details");
    }

    private static byte[] create(String password, String... pages) throws IOException {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            for (String text : pages) {
                PDPage page = new PDPage();
                document.addPage(page);
                try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                    stream.beginText();
                    stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    stream.newLineAtOffset(50, 700);
                    stream.showText(text);
                    stream.endText();
                }
            }
            if (password != null) {
                document.protect(new StandardProtectionPolicy("owner-password", password, new AccessPermission()));
            }
            document.save(bytes);
            return bytes.toByteArray();
        }
    }
}
