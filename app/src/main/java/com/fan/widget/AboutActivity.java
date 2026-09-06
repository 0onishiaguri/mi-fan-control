package com.fan.widget;

import android.app.Dialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Window;
import android.widget.ImageView;
import android.widget.LinearLayout;

public class AboutActivity extends BaseActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);

        // 原有：感谢名单点击事件
        LinearLayout itemThanks = findViewById(R.id.item_thanks);
        itemThanks.setOnClickListener(v -> {
            Intent intent = new Intent(this, WebViewActivity.class);
            intent.putExtra("url", "file:///android_asset/感谢名单.html");
            startActivity(intent);
        });

        // 原有：完整用户使用协议点击事件
        LinearLayout itemUserAgreement = findViewById(R.id.item_user_agreement);
        itemUserAgreement.setOnClickListener(v -> {
            Intent intent = new Intent(this, WebViewActivity.class);
            intent.putExtra("url", "file:///android_asset/user_agreement.html");
            startActivity(intent);
        });

        // 【新增】支持作者点击事件
        LinearLayout itemSupportAuthor = findViewById(R.id.item_support_author);
        itemSupportAuthor.setOnClickListener(v -> {
            showSupportAuthorDialog();
        });

        // 原有：开发者信息（往不忆）点击事件
        LinearLayout itemDeveloper = findViewById(R.id.item_developer);
        itemDeveloper.setOnClickListener(v -> {
            openDeveloperUrl("https://www.coolapk.com/u/22313034");
        });

        // 原有：开发者1 (yeg278) 点击事件
        LinearLayout itemDev1 = findViewById(R.id.item_dev_yeg278);
        itemDev1.setOnClickListener(v -> {
            openDeveloperUrl("https://www.coolapk.com/u/3805315");
        });

        // 原有：开发者2 (Smartisan_Apple) 点击事件
        LinearLayout itemDev2 = findViewById(R.id.item_dev_smartisan);
        itemDev2.setOnClickListener(v -> {
            openDeveloperUrl("https://www.coolapk.com/u/1404550");
        });

        // 原有：开发者3 (九磅九万便士) 点击事件
        LinearLayout itemDev3 = findViewById(R.id.item_dev_jiubang);
        itemDev3.setOnClickListener(v -> {
            openDeveloperUrl("https://www.coolapk.com/u/22493508");
        });
    }

    // 【新增方法】显示赞赏码对话框
    private void showSupportAuthorDialog() {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_support_author);

        // 设置背景透明，让 Dialog 看起来更美观
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        // 点击对话框外围可以关闭
        dialog.setCanceledOnTouchOutside(true);

        // 点击图片时也可以关闭（可选）
        ImageView qrCodeImage = dialog.findViewById(R.id.iv_support_qr);
        qrCodeImage.setOnClickListener(v -> dialog.dismiss());

        dialog.show();
    }

    // 统一处理：跳转酷安主页（优先酷安App，无则浏览器）
    private void openDeveloperUrl(String url) {
        String coolapkPackage = "com.coolapk.market";

        if (isAppInstalled(coolapkPackage)) {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.setPackage(coolapkPackage);
            try {
                startActivity(intent);
            } catch (Exception e) {
                openInBrowser(url);
            }
        } else {
            openInBrowser(url);
        }
    }

    // 辅助方法：检测应用是否安装
    private boolean isAppInstalled(String packageName) {
        try {
            getPackageManager().getPackageInfo(packageName, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // 辅助方法：调用浏览器打开
    private void openInBrowser(String url) {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        startActivity(intent);
    }
}