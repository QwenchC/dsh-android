package com.dsh.android;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.MimeTypeMap;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The workspace, as a list of files the device can open.
 *
 * The Harness writes a project into a directory the application owns, and
 * neither a file manager nor a viewer can reach that directory on its own. This
 * screen is the way out: it lists the workspace, opens a file with whatever
 * application the device already prefers for its type, and offers the explicit
 * "open with" chooser only as a second action.
 */
public final class FilesActivity extends Activity {

    /** The external storage provider's authority, for files that live on shared storage. */
    private static final String EXTERNAL_STORAGE_DOCUMENTS = "com.android.externalstorage.documents";

    private File root;
    private File current;
    private TextView path;
    private TextView note;
    private ArrayAdapter<File> adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(R.string.files_title);
        root = DshApp.runtime().workspace();
        current = root;
        View content = buildContentView();
        EdgeToEdge.apply(content);
        setContentView(content);
        refresh();
    }

    private View buildContentView() {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);

        path = new TextView(this);
        path.setTextSize(12f);
        path.setTypeface(android.graphics.Typeface.MONOSPACE);
        int padding = (int) (12 * getResources().getDisplayMetrics().density);
        path.setPadding(padding, padding, padding, padding / 2);
        column.addView(path);

        note = new TextView(this);
        note.setTextSize(12f);
        note.setPadding(padding, 0, padding, padding / 2);
        note.setVisibility(View.GONE);
        column.addView(note);

        ListView list = new ListView(this);
        adapter = new ArrayAdapter<File>(this, android.R.layout.simple_list_item_1, new ArrayList<>()) {
            @Override
            public View getView(int position, View recycled, ViewGroup parent) {
                TextView row = (TextView) super.getView(position, recycled, parent);
                File entry = getItem(position);
                if (entry != null) {
                    row.setText(entry.isDirectory() ? entry.getName() + "/" : entry.getName() + describe(entry));
                }
                return row;
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener(this::onEntry);
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            openWithChooser(adapter.getItem(position));
            return true;
        });
        column.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return column;
    }

    private String describe(File file) {
        long bytes = file.length();
        if (bytes < 1024) return "  " + bytes + " B";
        if (bytes < 1024 * 1024) return "  " + (bytes / 1024) + " KB";
        return "  " + (bytes / (1024 * 1024)) + " MB";
    }

    private void onEntry(AdapterView<?> parent, View view, int position, long id) {
        File entry = adapter.getItem(position);
        if (entry == null) return;
        if (entry.isDirectory()) {
            current = entry;
            refresh();
            return;
        }
        openDefault(entry);
    }

    /** List the current directory; a parent entry appears whenever one exists. */
    private void refresh() {
        path.setText(current.getAbsolutePath());
        List<File> entries = new ArrayList<>();
        if (!current.equals(root) && current.getParentFile() != null) {
            entries.add(current.getParentFile());
        }
        File[] children = current.listFiles();
        if (children != null) {
            Arrays.sort(children, (left, right) -> {
                if (left.isDirectory() != right.isDirectory()) return left.isDirectory() ? -1 : 1;
                return left.getName().compareToIgnoreCase(right.getName());
            });
            entries.addAll(Arrays.asList(children));
        }
        adapter.clear();
        adapter.addAll(entries);
        adapter.notifyDataSetChanged();

        // A workspace the application owns cannot be shown by any file manager,
        // so the screen says what would make it reachable instead of offering a
        // button that cannot work.
        boolean shared = !current.getAbsolutePath().startsWith(getFilesDir().getAbsolutePath());
        note.setVisibility(shared ? View.GONE : View.VISIBLE);
        note.setText(R.string.files_private_note);
    }

    /**
     * Open with whatever application the device already prefers for this type.
     *
     * A plain `ACTION_VIEW` is what lets the platform use — and remember — the
     * user's chosen application, so nothing here forces a chooser. When no
     * installed application claims the type at all, asking is pointless; the
     * file is offered for sharing instead, which is the one thing that still
     * gets it somewhere.
     */
    private void openDefault(File file) {
        Uri uri = uriFor(file);
        if (uri == null) {
            Toast.makeText(this, R.string.files_not_shareable, Toast.LENGTH_LONG).show();
            return;
        }
        Intent view = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mimeOf(file))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (view.resolveActivity(getPackageManager()) == null) {
            Toast.makeText(this, R.string.files_no_viewer, Toast.LENGTH_LONG).show();
            share(file);
            return;
        }
        try {
            startActivity(view);
        } catch (ActivityNotFoundException vanished) {
            share(file);
        }
    }

    /** The explicit chooser: the second action, not the first. */
    private void openWithChooser(File file) {
        if (file == null) return;
        if (file.isDirectory()) {
            openDirectory(file);
            return;
        }
        Uri uri = uriFor(file);
        if (uri == null) {
            Toast.makeText(this, R.string.files_not_shareable, Toast.LENGTH_LONG).show();
            return;
        }
        Intent view = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mimeOf(file))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(view, getString(R.string.files_open_with)));
        } catch (ActivityNotFoundException nothing) {
            share(file);
        }
    }

    /** Hand the file to another application as a share, when nothing can display it. */
    private void share(File file) {
        Uri uri = uriFor(file);
        if (uri == null) {
            Toast.makeText(this, R.string.files_not_shareable, Toast.LENGTH_LONG).show();
            return;
        }
        Intent send = new Intent(Intent.ACTION_SEND)
                .setType(mimeOf(file))
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(send, getString(R.string.files_share)));
        } catch (ActivityNotFoundException nothing) {
            Toast.makeText(this, R.string.files_no_handler, Toast.LENGTH_SHORT).show();
        }
    }

    /** Ask the device's file manager to show a directory. */
    private void openDirectory(File directory) {
        Uri uri = uriFor(directory);
        if (uri == null) {
            Toast.makeText(this, R.string.files_not_shareable, Toast.LENGTH_LONG).show();
            return;
        }
        Intent view = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, DocumentsContract.Document.MIME_TYPE_DIR)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(view);
        } catch (ActivityNotFoundException noManager) {
            Toast.makeText(this, R.string.files_no_manager, Toast.LENGTH_LONG).show();
        }
    }

    /**
     * A URI another application may read.
     *
     * Application-private files go through this application's provider, because
     * no other application can open a path inside the private directory. Files
     * on shared storage go through the external storage provider, which the
     * device's own file manager and viewers already understand.
     */
    private Uri uriFor(File file) {
        String path = file.getAbsolutePath();
        String served = new File(getFilesDir(), ProjectFilesProvider.SERVED_ROOT).getAbsolutePath();
        if (path.equals(served) || path.startsWith(served + File.separator)) {
            // The workspace the application owns, served by its own provider.
            String relative = path.substring(served.length() + 1).replace(File.separatorChar, '/');
            return new Uri.Builder()
                    .scheme("content")
                    .authority(getPackageName() + ProjectFilesProvider.AUTHORITY_SUFFIX)
                    .encodedPath("/" + relative)
                    .build();
        }
        String external = android.os.Environment.getExternalStorageDirectory().getAbsolutePath();
        if (path.startsWith(external + File.separator)) {
            // Shared storage, served by the provider the device already uses for it.
            String relative = path.substring(external.length() + 1);
            return DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_DOCUMENTS, "primary:" + relative);
        }
        // A workspace outside both is private to this application and has no
        // URI another application may read; the caller says so rather than
        // handing out one that cannot work.
        return null;
    }

    private String mimeOf(File file) {
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        if (dot >= 0) {
            String guessed = MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(name.substring(dot + 1).toLowerCase());
            if (guessed != null) return guessed;
        }
        return "application/octet-stream";
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, 1, 0, R.string.files_open_folder);
        menu.add(0, 2, 1, R.string.files_root);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case 1:
                openDirectory(current);
                return true;
            case 2:
                current = root;
                refresh();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    @Override
    public void onBackPressed() {
        if (!current.equals(root) && current.getParentFile() != null) {
            current = current.getParentFile();
            refresh();
            return;
        }
        super.onBackPressed();
    }
}
