package dev.dataclean.engine;

import dev.dataclean.engine.DatasetValidator.InvalidDataset;
import dev.dataclean.engine.DatasetValidator.Rows;
import org.apache.poi.ooxml.POIXMLException;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.zip.ZipInputStream;

final class ExcelRows {
    private static final long MAX_EXPANDED_BYTES = 32L * 1024 * 1024;
    private static final int MAX_ENTRIES = 1_000;

    private ExcelRows() {}

    static void read(byte[] payload, String sheetName, Rows rows) {
        try {
            checkArchiveSize(payload);
            try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(payload))) {
                readSheet(workbook, sheetName, rows);
            }
        } catch (IOException | POIXMLException | IllegalStateException | IllegalArgumentException e) {
            throw new InvalidDataset("Malformed or unsupported XLSX workbook");
        }
    }

    private static void checkArchiveSize(byte[] payload) throws IOException {
        try (var zip = new ZipInputStream(new ByteArrayInputStream(payload))) {
            int entries = 0;
            long expanded = 0;
            byte[] buffer = new byte[8192];
            while (zip.getNextEntry() != null) {
                if (++entries > MAX_ENTRIES) throw new InvalidDataset("XLSX contains too many ZIP entries");
                int size;
                while ((size = zip.read(buffer)) != -1) {
                    expanded += size;
                    if (expanded > MAX_EXPANDED_BYTES) throw new InvalidDataset("XLSX exceeds 32 MiB after decompression");
                }
            }
            if (entries == 0) throw new InvalidDataset("Malformed or unsupported XLSX workbook");
        }
    }

    private static void readSheet(XSSFWorkbook workbook, String sheetName, Rows rows) {
        if (workbook.getNumberOfSheets() == 0) throw new InvalidDataset("XLSX has no sheets");
        var sheet = sheetName == null ? workbook.getSheetAt(0) : workbook.getSheet(sheetName);
        if (sheet == null) throw new InvalidDataset("Requested XLSX sheet does not exist");
        var firstRow = sheet.getRow(0);
        if (firstRow == null) throw new InvalidDataset("XLSX must have a header on row 1");
        int width = firstRow.getLastCellNum();
        while (width > 0 && isBlank(firstRow.getCell(width - 1))) width--;
        var header = new ArrayList<String>();
        for (int index = 0; index < width; index++) {
            var cell = firstRow.getCell(index);
            if (cell == null || cell.getCellType() != CellType.STRING) {
                throw new InvalidDataset("XLSX header must contain column names as text");
            }
            header.add(cell.getStringCellValue());
        }
        var positions = rows.header(header);
        for (var row : sheet) {
            if (row.getRowNum() == 0) continue;
            boolean hasData = false;
            boolean hasExtraColumn = false;
            for (var cell : row) {
                if (cell.getCellType() == CellType.FORMULA) {
                    throw new InvalidDataset("XLSX formula on worksheet row " + (row.getRowNum() + 1));
                }
                if (!isBlank(cell)) {
                    hasData = true;
                    if (cell.getColumnIndex() >= width) hasExtraColumn = true;
                }
            }
            if (hasExtraColumn) rows.columnCount(() -> {
                var values = new ArrayList<String>();
                for (int column = 0; column < row.getLastCellNum(); column++) values.add(text(row.getCell(column)));
                return values;
            });
            else if (hasData) rows.accept(name -> text(row.getCell(positions.get(name))));
        }
    }

    private static boolean isBlank(Cell cell) {
        return cell == null || cell.getCellType() == CellType.BLANK
                || cell.getCellType() == CellType.STRING && cell.getStringCellValue().isBlank();
    }

    private static String text(Cell cell) {
        if (cell == null) return "";
        return switch (cell.getCellType()) {
            case BLANK -> "";
            case STRING -> cell.getStringCellValue();
            case BOOLEAN -> Boolean.toString(cell.getBooleanCellValue());
            case NUMERIC -> numeric(cell);
            default -> null;
        };
    }

    private static String numeric(Cell cell) {
        if (DateUtil.isCellDateFormatted(cell)) {
            var date = cell.getLocalDateTimeCellValue();
            return date.toLocalTime().equals(LocalTime.MIDNIGHT) ? date.toLocalDate().toString() : date.toString();
        }
        return BigDecimal.valueOf(cell.getNumericCellValue()).stripTrailingZeros().toPlainString();
    }
}
