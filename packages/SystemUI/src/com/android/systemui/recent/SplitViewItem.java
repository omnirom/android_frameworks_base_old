package com.android.systemui.recent;

import android.os.Parcel;
import android.os.Parcelable;

 /**
  * Class holding information about activities handled by split view
  */
public class SplitViewItem implements Parcelable {
    public int mTaskId;
    public boolean mIsSplitView;
    public int mSnap;
    public int mPositionX;
    public int mPositionY;
    public int mWidth;
    public int mHeight;

    public SplitViewItem() {
        // Do nothing
    }

    private SplitViewItem(Parcel in) {
        mTaskId = in.readInt();
        mIsSplitView = in.readInt() == 1;
        mSnap = in.readInt();
        mPositionX = in.readInt();
        mPositionY = in.readInt();
        mWidth = in.readInt();
        mHeight = in.readInt();
    }

    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(mTaskId);
        out.writeInt(mIsSplitView ? 1 : 0);
        out.writeInt(mSnap);
        out.writeInt(mPositionX);
        out.writeInt(mPositionY);
        out.writeInt(mWidth);
        out.writeInt(mHeight);
    }

    public static final Parcelable.Creator<SplitViewItem> CREATOR
            = new Parcelable.Creator<SplitViewItem>() {
        public SplitViewItem createFromParcel(Parcel in) {
            return new SplitViewItem(in);
        }

        public SplitViewItem[] newArray(int size) {
            return new SplitViewItem[size];
        }
     };
}
