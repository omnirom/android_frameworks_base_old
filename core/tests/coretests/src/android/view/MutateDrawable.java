/*
 * Copyright (C) 2009 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.view;

import android.app.Activity;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.Button;
import com.android.frameworks.coretests.R;

public class MutateDrawable extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout layout = new LinearLayout(this);

        Button ok = new Button(this);
        ok.setId(R.id.a);
        ok.setBackgroundDrawable(getResources().getDrawable(
                R.drawable.sym_now_playing_skip_forward_1));

        Button cancel = new Button(this);
        cancel.setId(R.id.b);
        cancel.setBackgroundDrawable(getResources().getDrawable(
                R.drawable.sym_now_playing_skip_forward_1));

        layout.addView(ok);
        layout.addView(cancel);

        ok.getBackground().mutate().setAlpha(127);

        setContentView(layout);
    }
}
