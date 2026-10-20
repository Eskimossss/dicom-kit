package ru.medeng.dicomtool.storagefixtures;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.UUID;

/** Java fixture: also runs in the separate test APK process without target APK Kotlin classes. */
public class Stage51DocumentsProvider extends DocumentsProvider {
    private static final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger> readOpens = new java.util.concurrent.ConcurrentHashMap<>();
    private static final String[] COLUMNS = {
        DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_FLAGS,
        DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED };
    @Override public boolean onCreate() { return true; }
    private File file(String id) {
        try {
            File base = new File(getContext().getFilesDir(), "synthetic-provider").getCanonicalFile();
            base.mkdirs();
            File f = new File(base, id).getCanonicalFile();
            if (!f.toPath().startsWith(base.toPath()) || f.equals(base)) throw new IllegalArgumentException("Unsafe fixture ID");
            if (!id.contains("/")) f.mkdirs();
            return f;
        } catch (IOException ex) { throw new IllegalStateException(ex); }
    }
    private void add(MatrixCursor cursor, String id) {
        File f = file(id);
        int flags = f.isDirectory() ? DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE | DocumentsContract.Document.FLAG_SUPPORTS_DELETE
            : DocumentsContract.Document.FLAG_SUPPORTS_WRITE | DocumentsContract.Document.FLAG_SUPPORTS_DELETE;
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : cursor.getColumnNames()) {
            Object value = null;
            if (column.equals(DocumentsContract.Document.COLUMN_DOCUMENT_ID)) value = id;
            else if (column.equals(DocumentsContract.Document.COLUMN_DISPLAY_NAME)) value = f.getName();
            else if (column.equals(DocumentsContract.Document.COLUMN_MIME_TYPE)) value = f.isDirectory() ? DocumentsContract.Document.MIME_TYPE_DIR : "application/dicom";
            else if (column.equals(DocumentsContract.Document.COLUMN_FLAGS)) value = flags;
            else if (column.equals(DocumentsContract.Document.COLUMN_SIZE)) value = id.startsWith("delayed") ? 0L : f.length();
            else if (column.equals(DocumentsContract.Document.COLUMN_LAST_MODIFIED)) value = f.lastModified();
            row.add(column, value);
        }
    }
    @Override public Cursor queryRoots(String[] projection) {
        MatrixCursor c = new MatrixCursor(projection == null ? new String[]{DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID, DocumentsContract.Root.COLUMN_TITLE, DocumentsContract.Root.COLUMN_FLAGS} : projection);
        MatrixCursor.RowBuilder row = c.newRow();
        for (String col : c.getColumnNames()) {
            Object value = null;
            if (col.equals(DocumentsContract.Root.COLUMN_ROOT_ID) || col.equals(DocumentsContract.Root.COLUMN_DOCUMENT_ID)) value = "normal";
            else if (col.equals(DocumentsContract.Root.COLUMN_TITLE)) value = "Synthetic storage safety tests";
            else if (col.equals(DocumentsContract.Root.COLUMN_FLAGS)) value = DocumentsContract.Root.FLAG_SUPPORTS_CREATE | DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD;
            row.add(col, value);
        }
        return c;
    }
    @Override public Cursor queryDocument(String id, String[] projection) {
        delayQuery(id);
        MatrixCursor c = new MatrixCursor(projection == null ? COLUMNS : projection); add(c, id); return c;
    }
    @Override public Cursor queryChildDocuments(String parent, String[] projection, String sortOrder) {
        delayQuery(parent);
        MatrixCursor c = new MatrixCursor(projection == null ? COLUMNS : projection);
        File[] children = file(parent).listFiles();
        if (children != null) for (File child : children) add(c, parent + "/" + child.getName());
        return c;
    }
    @Override public boolean isChildDocument(String parent, String id) { return id.startsWith(parent + "/"); }
    @Override public String createDocument(String parent, String mime, String name) throws FileNotFoundException {
        if (name.equals(".") || name.equals("..") || name.contains("/") || name.contains("\\")) throw new IllegalArgumentException("Unsafe name");
        if (file(parent + "/" + name).exists()) name = UUID.randomUUID() + "_" + name;
        String id = parent + "/" + name;
        try {
            boolean created = mime.equals(DocumentsContract.Document.MIME_TYPE_DIR) ? file(id).mkdir() : file(id).createNewFile();
            if (!created) throw new FileNotFoundException("Create failed");
        } catch (IOException ex) { throw new FileNotFoundException(ex.getMessage()); }
        return id;
    }
    @Override public void deleteDocument(String id) throws FileNotFoundException {
        // Deliberately recursively destructive: keep.txt test proves production never calls this for directories.
        if (!delete(file(id))) throw new FileNotFoundException("Delete failed");
    }
    private boolean delete(File f) {
        File[] children = f.listFiles();
        if (children != null) for (File child : children) if (!delete(child)) return false;
        return f.delete();
    }
    @Override public android.os.Bundle call(String method, String arg, android.os.Bundle extras) {
        if (method.equals("stage6-read-count") && arg != null) {
            android.os.Bundle result = new android.os.Bundle();
            result.putInt("count", readOpens.getOrDefault(arg, new java.util.concurrent.atomic.AtomicInteger()).get());
            return result;
        }
        if (method.startsWith("stage56-") && arg != null && arg.startsWith("stage56query") && !arg.contains("/")) {
            android.os.Bundle result = new android.os.Bundle();
            try {
                if (method.equals("stage56-block")) new File(getContext().getCacheDir(), arg + ".block").createNewFile();
                if (method.equals("stage56-release")) new File(getContext().getCacheDir(), arg + ".release").createNewFile();
                result.putBoolean("started", new File(getContext().getCacheDir(), arg + ".started").exists());
                return result;
            } catch (IOException ex) { throw new IllegalStateException(ex); }
        }
        if ((method.equals("stage54-status") || method.equals("stage54-release")) && arg.startsWith("stage54delay") && !arg.contains("/")) {
            android.os.Bundle result = new android.os.Bundle();
            getContext().getCacheDir().mkdirs();
            try {
                if (method.equals("stage54-release")) new File(getContext().getCacheDir(), arg + ".release").createNewFile();
                result.putBoolean("started", new File(getContext().getCacheDir(), arg + ".started").exists());
                return result;
            } catch (IOException ex) { throw new IllegalStateException(ex); }
        }
        return super.call(method, arg, extras);
    }
    private void delayQuery(String id) {
        if (!id.startsWith("stage56query")) return;
        String root = id.split("/", 2)[0];
        File cache = getContext().getCacheDir();
        if (!new File(cache, root + ".block").exists()) return;
        try {
            new File(cache, root + ".started").createNewFile();
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
            while (!new File(cache, root + ".release").exists() && System.nanoTime() < deadline) Thread.sleep(10);
        } catch (IOException | InterruptedException ex) { throw new IllegalStateException(ex); }
    }
    @Override public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal signal) throws FileNotFoundException {
        if (mode.equals("r")) readOpens.computeIfAbsent(id.split("/", 2)[0], key -> new java.util.concurrent.atomic.AtomicInteger()).incrementAndGet();
        if (mode.equals("r") && id.startsWith("stage54delay")) {
            String root = id.substring(0, id.indexOf('/'));
            File started = new File(getContext().getCacheDir(), root + ".started");
            File release = new File(getContext().getCacheDir(), root + ".release");
            try {
                getContext().getCacheDir().mkdirs();
                started.createNewFile();
                long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
                while (!release.exists() && System.nanoTime() < deadline) Thread.sleep(10);
            } catch (IOException | InterruptedException ex) { throw new FileNotFoundException("Synthetic delayed read interrupted"); }
        }
        final File f = file(id);
        if (mode.contains("w") && id.startsWith("fail")) throw new FileNotFoundException("ENOSPC synthetic provider failure");
        if (mode.contains("w") && id.startsWith("short") && f.getName().endsWith(".dcm")) {
            try {
                final ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createReliablePipe();
                new Thread(() -> {
                    try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(pipe[0]); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                        byte[] buf = new byte[4096]; int n;
                        while ((n = input.read(buf)) != -1) bytes.write(buf, 0, n);
                        byte[] content = bytes.toByteArray();
                        try (FileOutputStream out = new FileOutputStream(f)) { out.write(Arrays.copyOf(content, content.length / 2)); }
                    } catch (IOException ex) { throw new IllegalStateException(ex); }
                }, "SyntheticShortWriter").start();
                return pipe[1];
            } catch (IOException ex) { throw new FileNotFoundException(ex.getMessage()); }
        }
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.parseMode(mode));
    }
}
