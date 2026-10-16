package org.lujarch.probe;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;

/** A sign-in form, as apps have one: a user name and a password field marked for autofill. */
public class Login extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
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
        Button sign = new Button(this);
        sign.setText("Sign in");
        sign.setOnClickListener(v -> {
            Probe.record(this, "login", user.getText() + "/" + pass.getText());
            finish();
        });
        box.addView(user);
        box.addView(pass);
        box.addView(sign);
        setContentView(box);
    }
}
