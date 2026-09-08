package com.fan.widget;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 在线检查软件更新页：
 * 1. 后台请求 GitHub Releases API 获取最新版本；
 * 2. 与本地版本号比较，有新版本时显示底部悬浮"下载更新"按钮；
 * 3. DownloadManager 下载 APK → FileProvider 拉起系统安装器。
 */
public class CheckUpdateActivity extends BaseActivity {

    private static final String API_URL =
            "https://api.github.com/repos/HOSHINO-LUYI/mi-fan-control/releases/latest";
    private static final String FILE_PROVIDER_AUTH = "com.fan.widget.fileprovider";
    private static final String APK_FILE_NAME = "MiFanControl_update.apk";

    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();

    private TextView tvCurrentVersion, tvLatestVersion, tvLatestTime, tvStatusText, tvUpdateInfo;
    private ProgressBar progressBar;
    private LinearLayout updateInfoCard;
    private Button btnDownload, btnRetry;

    private String mLatestVersion = "";
    private String mDownloadUrl = "";
    private long mDownloadId = -1;
    private DownloadManager mDownloadManager;
    private BroadcastReceiver mDownloadReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_check_update);
        mDownloadManager = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);

        tvCurrentVersion = findViewById(R.id.tv_current_version);
        tvLatestVersion = findViewById(R.id.tv_latest_version);
        tvLatestTime = findViewById(R.id.tv_latest_time);
        tvStatusText = findViewById(R.id.tv_status_text);
        tvUpdateInfo = findViewById(R.id.tv_update_info);
        progressBar = findViewById(R.id.progress_bar);
        updateInfoCard = findViewById(R.id.update_info_card);
        btnDownload = findViewById(R.id.btn_download);
        btnRetry = findViewById(R.id.btn_retry);

        tvCurrentVersion.setText(BuildConfig.VERSION_NAME);
        btnRetry.setOnClickListener(v -> checkUpdate());
        btnDownload.setOnClickListener(v -> onDownloadClick());

        checkUpdate();
    }

    private void checkUpdate() {
        setChecking(true);
        mExecutor.execute(() -> {
            String json;
            try {
                json = fetchLatestRelease();
            } catch (Exception e) {
                json = "";
            }
            final String result = json;
            runOnUiThread(() -> handleResult(result));
        });
    }

    // 请求 GitHub Releases API，失败返回空串
    private String fetchLatestRelease() throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(API_URL).openConnection();
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        conn.setRequestProperty("User-Agent", "MiFanControl");
        conn.setRequestProperty("Accept", "application/vnd.github+json");
        int code = conn.getResponseCode();
        if (code != 200) {
            conn.disconnect();
            return "";
        }
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line);
        }
        reader.close();
        conn.disconnect();
        return sb.toString();
    }

    private void handleResult(String json) {
        if (json.isEmpty()) {
            setChecking(false);
            tvStatusText.setText("检查失败，请检查网络后重试");
            tvStatusText.setTextColor(color(R.color.update_fail));
            btnRetry.setVisibility(View.VISIBLE);
            return;
        }
        try {
            JSONObject obj = new JSONObject(json);
            mLatestVersion = obj.optString("tag_name", "");
            String published = obj.optString("published_at", "");
            if (published.length() >= 10) {
                published = published.substring(0, 10);
            }
            String body = obj.optString("body", "").trim();
            JSONArray assets = obj.optJSONArray("assets");
            if (assets != null && assets.length() > 0) {
                mDownloadUrl = assets.getJSONObject(0).optString("browser_download_url", "");
            }

            tvLatestVersion.setText(mLatestVersion.isEmpty() ? "未知" : mLatestVersion);
            tvLatestTime.setText(published.isEmpty() ? "" : "发布时间：" + published);
            tvUpdateInfo.setText(body.isEmpty() ? "暂无更新说明" : body);

            setChecking(false);
            if (compareVersion(BuildConfig.VERSION_NAME, mLatestVersion) >= 0) {
                // 已是最新版本
                tvStatusText.setText("已是最新版本");
                tvStatusText.setTextColor(color(R.color.update_ok));
                btnDownload.setVisibility(View.GONE);
                updateInfoCard.setVisibility(View.GONE);
            } else {
                // 发现新版本
                tvStatusText.setText("发现新版本 " + mLatestVersion);
                tvStatusText.setTextColor(color(R.color.text_primary));
                btnDownload.setVisibility(View.VISIBLE);
                updateInfoCard.setVisibility(View.VISIBLE);
            }
        } catch (Exception e) {
            tvStatusText.setText("检查失败，数据解析异常");
            tvStatusText.setTextColor(color(R.color.update_fail));
            btnRetry.setVisibility(View.VISIBLE);
        }
    }

    private void setChecking(boolean checking) {
        progressBar.setVisibility(checking ? View.VISIBLE : View.GONE);
        btnRetry.setVisibility(View.GONE);
        if (checking) {
            tvStatusText.setText("正在检查更新...");
            tvStatusText.setTextColor(color(R.color.text_secondary));
            btnDownload.setVisibility(View.GONE);
            updateInfoCard.setVisibility(View.GONE);
        }
    }

    private int color(int resId) {
        return androidx.core.content.ContextCompat.getColor(this, resId);
    }

    // 版本号逐段数字比较：local < remote 返回 -1；相等 0；local 大返回 1
    private int compareVersion(String local, String remote) {
        String[] l = local.replaceAll("[^0-9.]", "").split("\\.");
        String[] r = remote.replaceAll("[^0-9.]", "").split("\\.");
        int n = Math.max(l.length, r.length);
        for (int i = 0; i < n; i++) {
            int a = i < l.length && !l[i].isEmpty() ? Integer.parseInt(l[i]) : 0;
            int b = i < r.length && !r[i].isEmpty() ? Integer.parseInt(r[i]) : 0;
            if (a != b) {
                return a < b ? -1 : 1;
            }
        }
        return 0;
    }

    private void onDownloadClick() {
        if (mDownloadUrl.isEmpty()) {
            tvStatusText.setText("暂无可用的下载地址，请重试");
            tvStatusText.setTextColor(color(R.color.update_fail));
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !getPackageManager().canRequestPackageInstalls()) {
            // 引导用户开启"允许安装未知来源应用"
            Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
            return;
        }
        startDownload();
    }

    private void startDownload() {
        try {
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(mDownloadUrl));
            req.setTitle("MiFan Control 更新");
            req.setDescription("正在下载 " + mLatestVersion + " ...");
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setAllowedOverMetered(true);
            req.setAllowedOverRoaming(false);
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, APK_FILE_NAME);
            mDownloadId = mDownloadManager.enqueue(req);
            btnDownload.setEnabled(false);
            btnDownload.setText("下载中...");
        } catch (Exception e) {
            tvStatusText.setText("下载启动失败，请重试");
            tvStatusText.setTextColor(color(R.color.update_fail));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mDownloadReceiver == null) {
            IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
            mDownloadReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                    if (id != mDownloadId) {
                        return;
                    }
                    handleDownloadComplete();
                }
            };
            registerReceiver(mDownloadReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        }
    }

    private void handleDownloadComplete() {
        DownloadManager.Query query = new DownloadManager.Query().setFilterById(mDownloadId);
        try (Cursor cursor = mDownloadManager.query(query)) {
            if (cursor == null || !cursor.moveToFirst()) {
                return;
            }
            int status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            if (status == DownloadManager.STATUS_SUCCESSFUL) {
                String uri = cursor.getString(
                        cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI));
                installApk(Uri.parse(uri));
            } else if (status == DownloadManager.STATUS_FAILED) {
                runOnUiThread(() -> {
                    btnDownload.setEnabled(true);
                    btnDownload.setText("下载失败，点击重试");
                    tvStatusText.setText("下载失败，请检查网络后重试");
                    tvStatusText.setTextColor(color(R.color.update_fail));
                });
            }
        } catch (Exception ignored) {
        }
    }

    private void installApk(Uri localUri) {
        try {
            File apkFile = null;
            if ("file".equals(localUri.getScheme())) {
                apkFile = new File(localUri.getPath());
            } else {
                // content:// 场景：通过查询获取真实路径
                DownloadManager.Query query = new DownloadManager.Query().setFilterById(mDownloadId);
                try (Cursor c = mDownloadManager.query(query)) {
                    if (c != null && c.moveToFirst()) {
                        int idx = c.getColumnIndex(DownloadManager.COLUMN_LOCAL_FILENAME);
                        if (idx >= 0) {
                            String path = c.getString(idx);
                            if (path != null) {
                                apkFile = new File(path);
                            }
                        }
                    }
                }
            }
            if (apkFile == null || !apkFile.exists()) {
                return;
            }
            Uri fileUri = FileProvider.getUriForFile(this, FILE_PROVIDER_AUTH, apkFile);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(fileUri, "application/vnd.android.package-archive");
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(intent);
        } catch (Exception e) {
            runOnUiThread(() -> {
                tvStatusText.setText("安装失败，请手动打开下载的安装包");
                tvStatusText.setTextColor(color(R.color.update_fail));
            });
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mDownloadReceiver != null) {
            try {
                unregisterReceiver(mDownloadReceiver);
            } catch (Exception ignored) {
            }
            mDownloadReceiver = null;
        }
        mExecutor.shutdownNow();
    }
}
