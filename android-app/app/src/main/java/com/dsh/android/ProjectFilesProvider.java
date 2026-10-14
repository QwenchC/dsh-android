package com.dsh.android;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/**
 * Read-only access to the files the Harness generated.
 *
 * A viewer or a share target cannot open a path inside this application's
 * private directory, so those files travel as content URIs this provider
 * serves. Only the workspace is reachable, every resolved path is proved to be
 * inside it, and nothing is writable.
 */
public final class ProjectFilesProvider extends ContentProvider {

    /** Authority suffix; the manifest declares the same value. */
    static final String AUTHORITY_SUFFIX = ".files";

    /**
     * The only directory this provider serves, relative to the application's
     * files root. It is deliberately the workspace and nothing else: the same
     * subtree holds the Harness home, whose credentials file must never be
     * reachable through a URI this application hands out.
     */
    static final String SERVED_ROOT = "dsh/workspace";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("project files are read-only");
        return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        File resolved;
        try {
            resolved = resolve(uri);
        } catch (FileNotFoundException outsideServedSubtree) {
            // The platform contract has no way to report this here; a null type
            // is the documented answer for an unknown URI.
            return null;
        }
        String name = resolved.getName();
        int dot = name.lastIndexOf('.');
        if (dot >= 0) {
            String guessed = MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(name.substring(dot + 1).toLowerCase());
            if (guessed != null) return guessed;
        }
        return "application/octet-stream";
    }

    /**
     * Resolve a content URI to the file it names.
     * @throws FileNotFoundException when the path escapes the served subtree.
     */
    private File resolve(Uri uri) throws FileNotFoundException {
        File root = new File(attachedContext().getFilesDir(), SERVED_ROOT);
        File candidate = new File(root, uri.getPath() == null ? "" : uri.getPath());
        try {
            String rootPath = root.getCanonicalPath() + File.separator;
            String candidatePath = candidate.getCanonicalPath();
            if (!candidatePath.startsWith(rootPath)) {
                throw new FileNotFoundException("path outside the served subtree: " + uri);
            }
            return candidate;
        } catch (IOException cannotResolve) {
            throw new FileNotFoundException("cannot resolve " + uri + ": " + cannotResolve.getMessage());
        }
    }

    /**
     * The context this provider is attached to.
     *
     * Deliberately not named `requireContext`: the platform's own method of
     * that name is final and newer than this application's minimum API level,
     * so reaching for it would fail on the oldest supported devices.
     */
    private android.content.Context attachedContext() {
        android.content.Context context = getContext();
        if (context == null) throw new IllegalStateException("provider is not attached");
        return context;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        throw new UnsupportedOperationException("project files are read-only");
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("project files are read-only");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("project files are read-only");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("project files are read-only");
    }
}
