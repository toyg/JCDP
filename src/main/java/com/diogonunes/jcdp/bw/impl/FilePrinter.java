/*
 * MIT License
 *
 * Copyright (c) 2019 Giacomo Lacava
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.diogonunes.jcdp.bw.impl;

import com.diogonunes.jcdp.bw.api.AbstractPrinter;

import java.io.*;
import java.nio.charset.Charset;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.HashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * an AbstractPrinter that outputs to file.
 * <p>
 * This allows one to use JCDP as an actual logging mechanism,
 * so it doesn't need to be coupled with more heavyweight solutions
 * in the simple case.
 * <p>
 * A SLF4J adapter is available separately, which can be configured to
 * log simultaneously to terminal and file with one call.
 * See <a href="https://github.com/toyg/slf4j-jcdp">SLF4J-JCDP</a> for details.
 */
public class FilePrinter extends AbstractPrinter {

    private class CleanupThread extends Thread {
        private final LockableWriter _writer;

        CleanupThread(LockableWriter theWriter) {
            _writer = theWriter;
        }

        @Override
        public void run() {
            _writer.close();
        }
    }


    /**
     * writer using a {@link ReentrantLock} for safe multithreading
     */
    private class LockableWriter implements AutoCloseable, Flushable, Appendable {
        private ReentrantLock lock;
        private OutputStream outStream;
        private PrintWriter writer;
        private File file;
        private boolean open = true;

        /**
         * constructor
         *
         * @param file {@link File} for output
         * @throws FileNotFoundException if file cannot be found
         */
        public LockableWriter(File file) throws FileNotFoundException, SecurityException {
            Charset charset;
            try {
                charset = Charset.forName("UTF-8");
            } catch (IllegalArgumentException e) {
                // utf not available, use default
                charset = Charset.defaultCharset();
            }
            this.file = file;
            this.lock = new ReentrantLock();
            this.outStream = new FileOutputStream(file, true);
            this.writer = new PrintWriter(new BufferedWriter(new OutputStreamWriter(outStream, charset)));
            // register a cleanup job to close streams
            Runtime.getRuntime().addShutdownHook(new CleanupThread(this));
        }

        /**
         * guard against threads trying to write to closed streams
         */
        public boolean isOpen() {
            return this.open;
        }

        /**
         * write string to file
         *
         * @param str the string to output
         */
        public void write(String str) {
            lock.lock();
            if (this.isOpen())
                writer.write(str);
            lock.unlock();
        }

        @Override
        public void flush() {
            lock.lock();
            if (this.isOpen())
                writer.flush();
            lock.unlock();
        }

        @Override
        public Appendable append(CharSequence csq) {
            lock.lock();
            if (this.isOpen())
                writer.append(csq);
            lock.unlock();
            return this;
        }

        @Override
        public Appendable append(CharSequence csq, int start, int end) {
            lock.lock();
            if (this.isOpen())
                writer.append(csq, start, end);
            lock.unlock();
            return this;
        }

        @Override
        public Appendable append(char c) {
            lock.lock();
            if (this.isOpen())
                writer.append(c);
            lock.unlock();
            return this;
        }

        /**
         * cleanup operations, will be called on shutdown
         */
        public void close() {
            lock.lock();
            try {
                if (this.writer != null) this.writer.close();
                if (this.outStream != null) this.outStream.close();
            } catch (IOException e) {
                // at this point outStream is gone, so it doesn't matter
            }
            this.open = false;
            lock.unlock();
        }

        /**
         * accessor to file details
         */
        public File getFile() {
            return file;
        }
    }


    /* we keep a static map of file paths -> threadsafe output streams,
     * so multiple instances can orderly write to the same file. */
    static private HashMap<String, LockableWriter> lockRegistry = new HashMap<>();

    // the writer for this instance
    private LockableWriter writer;

    /**
     * constructor
     *
     * @param builder {@link Builder} with the necessary configuration
     * @throws FileNotFoundException if the file cannot be created or accessed
     * @throws SecurityException     if the file cannot be created or accessed
     */
    public FilePrinter(Builder builder) throws FileNotFoundException, SecurityException {
        setFile(builder._logFile);
        setLevel(builder._level);
        setTimestamping(builder._timestampFlag);
        setDateFormat(builder._dateFormat);
    }

    private void setFile(File outFile) throws FileNotFoundException, SecurityException {
        // if there is no writer associated to this file, create it;
        // then retrieve a reference
        lockRegistry.putIfAbsent(outFile.getAbsolutePath(), new LockableWriter(outFile));
        this.writer = lockRegistry.get(outFile.getAbsolutePath());
    }


    // =========
    // BUILDER
    // =========

    /**
     * Builder pattern: allows the caller to specify the attributes that it
     * wants to change and keep the default values ​​in the others.
     */
    public static class Builder {
        // required parameters
        private File _logFile;
        private int _level;
        private boolean _timestampFlag;
        // optional parameters, initialized to default values
        private DateFormat _dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

        /**
         * The Printer created uses, by default, timestamping format according
         * to ISO 8601.
         *
         * @param logFile {@link java.io.File} to log messages
         * @param level   specifies the maximum level of debug this printer can
         *                print.
         * @param tsFlag  true, if you want a timestamp before each message.
         * @see <a href=
         * "http://www.iso.org/iso/catalogue_detail.htm?csnumber=26780">ISO
         * 8601</a>
         */
        public Builder(File logFile, int level, boolean tsFlag) {
            _logFile = logFile;
            _level = level;
            _timestampFlag = tsFlag;
        }

        /**
         * @param df the printing format of the timestamp.
         * @return the builder.
         */
        public Builder withFormat(DateFormat df) {
            this._dateFormat = df;
            return this;
        }

        /**
         * @return a new instance of TerminalPrinter.
         */
        public FilePrinter build() throws FileNotFoundException, SecurityException {
            return new FilePrinter(this);
        }
    }

    private void printWithLevel(Object msg, int level) {
        if (isLoggingTimestamps()) printTimestamp();
        this.writer.write("[ " + level + " ] " + msg.toString());
    }


    /* -- OUTPUT METHODS -- */
    @Override
    public void printTimestamp() {
        this.writer.write(getDateFormatted() + " ");
    }

    @Override
    public void printErrorTimestamp() {
        this.writer.write(getDateFormatted() + " ");
    }

    @Override
    public void print(Object msg) {
        if (isLoggingTimestamps())
            printTimestamp();
        this.writer.write(msg.toString());
    }

    @Override
    public void println(Object msg) {
        if (isLoggingTimestamps())
            printTimestamp();
        this.writer.write(msg.toString() + System.lineSeparator());
    }

    @Override
    public void errorPrint(Object msg) {
        print(msg.toString());
    }

    @Override
    public void errorPrintln(Object msg) {
        println(msg.toString());
    }

    @Override
    public void debugPrint(Object msg) {
        if (isLoggingDebug()) print(msg.toString());
    }

    @Override
    public void debugPrint(Object msg, int level) {
        if (isLoggingDebug() && canPrint(level))
            printWithLevel(msg.toString(), level);
    }

    @Override
    public void debugPrintln(Object msg) {
        if (isLoggingDebug())
            print(msg.toString() + System.lineSeparator());
    }

    @Override
    public void debugPrintln(Object msg, int level) {
        if (isLoggingDebug() && canPrint(level))
            printWithLevel(msg.toString() + System.lineSeparator(), level);
    }

    @Override
    public String toString() {
        return getClass().getSimpleName()
                + " | level: " + getLevel()
                + " | timestamping: " + isLoggingTimestamps()
                + " | " + writer.getFile().getAbsolutePath();
    }

}