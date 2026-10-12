package dev.jdal.driver.csv;

import dev.jdal.core.JdalException;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads CSV records: RFC 4180 quoting with line breaks inside quotes, tolerant like GDAL about
 * double quotes in the middle of an unquoted field, empty lines skipped. Tracks the UTF-8 offset
 * after each record.
 */
final class CsvRecords implements AutoCloseable {

  /**
   * One record: field texts, whether each field was quoted, and the byte offset after the record.
   */
  record Record(List<String> fields, List<Boolean> quoted, long endOffset) {}

  private final Reader in;
  private final char sep;
  private final boolean mergeSeparators;
  private int peeked = -2;
  private long offset;
  private boolean first = true;

  CsvRecords(Reader in, char sep, boolean mergeSeparators) {
    this.in = in;
    this.sep = sep;
    this.mergeSeparators = mergeSeparators;
  }

  private int peek() {
    if (peeked == -2) {
      try {
        peeked = in.read();
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
      if (first) {
        first = false;
        if (peeked == '\uFEFF') {
          offset += 3;
          peeked = -2;
          return peek(); // skip the byte order mark
        }
      }
    }
    return peeked;
  }

  private int read() {
    int c = peek();
    peeked = -2;
    if (c >= 0) offset += c < 0x80 ? 1 : c < 0x800 ? 2 : Character.isSurrogate((char) c) ? 2 : 3;
    return c;
  }

  /** The next record, or null at the end of the file. */
  Record next() {
    while (true) {
      if (peek() < 0) return null;
      List<String> fields = new ArrayList<>();
      List<Boolean> quoted = new ArrayList<>();
      boolean sawAnything = false;
      while (true) {
        StringBuilder sb = new StringBuilder();
        boolean q = false;
        int c = peek();
        if (c == '"') {
          q = true;
          sawAnything = true;
          read();
          while (true) {
            c = read();
            if (c < 0) {
              throw new JdalException(
                  "CSV file has unbalanced number of double-quotes. Corrupted data will likely be"
                      + " returned");
            }
            if (c == '"') {
              if (peek() == '"') {
                read();
                sb.append('"');
              } else {
                break;
              }
            } else {
              sb.append((char) c);
            }
          }
          // anything between the closing quote and the separator is kept as is
          while ((c = peek()) >= 0 && c != sep && c != '\n' && c != '\r') sb.append((char) read());
        } else {
          while ((c = peek()) >= 0 && c != sep && c != '\n' && c != '\r') {
            sb.append((char) read());
            sawAnything = true;
          }
        }
        fields.add(sb.toString());
        quoted.add(q);
        c = peek();
        if (c == sep) {
          read();
          sawAnything = true;
          if (mergeSeparators) {
            while (peek() == sep) read();
          }
          continue;
        }
        if (c == '\r') {
          read();
          if (peek() == '\n') read();
        } else if (c == '\n') {
          read();
        }
        break;
      }
      if (!sawAnything && fields.size() == 1) continue; // empty line
      return new Record(fields, quoted, offset);
    }
  }

  @Override
  public void close() {
    try {
      in.close();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
