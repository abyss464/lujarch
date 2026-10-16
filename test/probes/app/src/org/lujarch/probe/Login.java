package org.lujarch.probe;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;

/**
 * A sign-in form, as apps have one: a user name, a password and a one-time code field marked for
 * autofill. With the extra "web" (an address), the form is a web page of that address instead, as
 * a browser shows a site's sign-in page; with "over_lock", it shows over the lock screen, as a
 * call or a payment app does.
 */
public class Login extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (getIntent().getBooleanExtra("over_lock", false)) setShowWhenLocked(true);
        String web = getIntent().getStringExtra("web");
        if (web != null) {
            WebView page = new WebView(this);
            page.getSettings().setJavaScriptEnabled(true);
            page.addJavascriptInterface(this, "probe");
            // Where the fields are on the screen, for a test to tap them: a page's fields are not
            // in the accessibility tree a test reads.
            page.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView view, String url) {
                    int[] at = new int[2];
                    view.getLocationOnScreen(at);
                    view.evaluateJavascript("JSON.stringify(['u','p','c'].map(i => {"
                            + "const r = document.getElementById(i).getBoundingClientRect();"
                            + "return [r.left + r.width / 2, r.top + r.height / 2]"
                            + ".map(v => Math.round(v * devicePixelRatio));}))",
                            v -> Probe.record(Login.this, "fields", at[0] + "," + at[1] + ":" + v));
                }
            });
            page.loadDataWithBaseURL(web, FORM, "text/html", "utf-8", null);
            setContentView(page);
            return;
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(48, 200, 48, 48);
        EditText user = new EditText(this);
        user.setHint("User name");
        user.setAutofillHints(View.AUTOFILL_HINT_USERNAME);
        user.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        EditText pass = new EditText(this);
        pass.setHint("Password");
        pass.setAutofillHints(View.AUTOFILL_HINT_PASSWORD);
        pass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        EditText code = new EditText(this);
        code.setHint("One-time code");
        code.setAutofillHints("2faAppOTPCode");
        code.setInputType(InputType.TYPE_CLASS_NUMBER);
        Button sign = new Button(this);
        sign.setText("Sign in");
        sign.setOnClickListener(v -> signedIn(user.getText() + "/" + pass.getText() + "/" + code.getText()));
        box.addView(user);
        box.addView(pass);
        box.addView(code);
        box.addView(sign);
        setContentView(box);
    }

    @JavascriptInterface
    public void signedIn(String what) {
        Probe.record(this, "login", what);
        finish();
    }

    private static final String FORM = "<!doctype html><meta name=viewport content='width=device-width'>"
            + "<form onsubmit=\"probe.signedIn(u.value+'/'+p.value+'/'+c.value);return false\""
            + " style='font-size:20px;padding:120px 40px 40px'>"
            + "<p><input id=u name=username autocomplete=username placeholder='User name' aria-label='User name'>"
            + "<p><input id=p name=password type=password autocomplete=current-password placeholder=Password"
            + " aria-label=Password>"
            + "<p><input id=c name=otp autocomplete=one-time-code placeholder='One-time code'"
            + " aria-label='One-time code'>"
            + "<p><button>Sign in</button></form>";
}
