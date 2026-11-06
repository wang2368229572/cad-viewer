package app.cadlook;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;

import app.cadlook.core.CadDocument;
import app.cadlook.core.CadReadException;
import app.cadlook.core.DxfParser;

public final class MainActivity extends Activity {
    private static final int OPEN_DXF = 41;

    private CadView cadView;
    private View emptyView;
    private TextView statusView;
    private View controls;
    private Button orientButton;
    private boolean chromeHidden;
    private int loadGeneration;
    private final Runnable hideStatus = new Runnable() {
        @Override
        public void run() {
            statusView.setVisibility(View.GONE);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        useFullScreen();
        setContentView(R.layout.activity_main);
        cadView = findViewById(R.id.cad);
        emptyView = findViewById(R.id.empty);
        statusView = findViewById(R.id.status);
        controls = findViewById(R.id.controls);
        cadView.setChromeListener(new CadView.ChromeListener() {
            @Override
            public void onToggleChrome() {
                setChromeHidden(!chromeHidden);
            }
        });
        findViewById(R.id.open).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openPicker();
            }
        });
        findViewById(R.id.fit).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cadView.fit();
            }
        });
        orientButton = findViewById(R.id.orient);
        orientButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleOrientation();
            }
        });
        updateOrientLabel();
        Uri incoming = getIntent() == null ? null : getIntent().getData();
        if (incoming != null) {
            load(incoming);
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateOrientLabel();
        applySystemBars(chromeHidden);
        cadView.fit();
    }

    private void setChromeHidden(boolean hidden) {
        chromeHidden = hidden;
        controls.setVisibility(hidden ? View.GONE : View.VISIBLE);
        if (hidden) {
            statusView.removeCallbacks(hideStatus);
            statusView.setVisibility(View.GONE);
        }
        applySystemBars(hidden);
    }

    private void applySystemBars(boolean hidden) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller == null) {
                return;
            }
            int bars = WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars();
            if (hidden) {
                controller.hide(bars);
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            } else {
                controller.show(bars);
            }
            return;
        }
        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        if (hidden) {
            flags |= View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION;
        }
        getWindow().getDecorView().setSystemUiVisibility(flags);
    }

    private void useFullScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams attributes = getWindow().getAttributes();
            attributes.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(attributes);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    private void toggleOrientation() {
        if (isLandscape()) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        } else {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        }
    }

    private boolean isLandscape() {
        return getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
    }

    private void updateOrientLabel() {
        orientButton.setText(isLandscape() ? "竖屏" : "横屏");
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && intent.getData() != null) {
            load(intent.getData());
        }
    }

    private void openPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/dxf",
                "image/vnd.dxf",
                "application/acad",
                "image/vnd.dwg",
                "application/octet-stream",
                "text/plain"
        });
        startActivityForResult(intent, OPEN_DXF);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == OPEN_DXF && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException ignored) {
                // 有些来源不允许记住权限，这次读取仍然有效。
            }
            load(uri);
        }
    }

    private void load(final Uri uri) {
        final int generation = ++loadGeneration;
        final String name = displayName(uri);
        statusView.setText("正在读取…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    if (in == null) {
                        throw new CadReadException("打不开这个文件。");
                    }
                    final CadDocument document = DxfParser.parse(in);
                    if (generation != loadGeneration) {
                        return;
                    }
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            show(document, name);
                        }
                    });
                } catch (final Exception e) {
                    if (generation != loadGeneration) {
                        return;
                    }
                    final String message = e instanceof CadReadException
                            ? e.getMessage()
                            : "无法读取这个文件。";
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            fail(message);
                        }
                    });
                }
            }
        }, "cad-read").start();
    }

    private void show(CadDocument document, String name) {
        emptyView.setVisibility(View.GONE);
        cadView.setDocument(document);
        String title = name == null || name.isEmpty() ? "图纸" : name;
        showStatus(title, false);
    }

    private void fail(String message) {
        showStatus(message, true);
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private void showStatus(String text, boolean sticky) {
        statusView.removeCallbacks(hideStatus);
        statusView.setText(text);
        statusView.setVisibility(View.VISIBLE);
        if (!sticky) {
            statusView.postDelayed(hideStatus, 2000);
        }
    }

    private String displayName(Uri uri) {
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getString(0);
            }
        } catch (RuntimeException ignored) {
            return null;
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return null;
    }
}
