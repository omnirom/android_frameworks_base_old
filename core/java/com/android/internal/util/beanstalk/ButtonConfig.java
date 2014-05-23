
package com.android.internal.util.beanstalk;

public class ButtonConfig {

    private String mClickAction;
    private String mClickActionDescription;
    private String mLongpressAction;
    private String mLongpressActionDescription;
    private String mIconUri;

    public ButtonConfig(String clickAction, String clickActionDescription,
                    String longpressAction, String longpressActionDescription, String iconUri) {
        mClickAction = clickAction;
        mClickActionDescription = clickActionDescription;
        mLongpressAction = longpressAction;
        mLongpressActionDescription = longpressActionDescription;
        mIconUri = iconUri;
    }

    @Override
    public String toString() {
        return mClickActionDescription;
    }

    public String getClickAction() {
        return mClickAction;
    }

    public String getClickActionDescription() {
        return mClickActionDescription;
    }

    public String getLongpressAction() {
        return mLongpressAction;
    }

    public String getLongpressActionDescription() {
        return mLongpressActionDescription;
    }

    public String getIcon() {
        return mIconUri;
    }

    public void setClickAction(String action) {
        mClickAction = action;
    }

    public void setClickActionDescription(String description) {
        mClickActionDescription = description;
    }

    public void setLongpressAction(String action) {
        mLongpressAction = action;
    }

    public void setLongpressActionDescription(String description) {
        mLongpressActionDescription = description;
    }

    public void setIcon(String iconUri) {
        mIconUri = iconUri;
    }

}
