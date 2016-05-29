package com.android.systemui.screenshot;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.systemui.R;

/**
 * Created by DanielHuber on 16.04.2016.
 */
public class ColorArrayAdapter extends ArrayAdapter<Integer> {
    private Integer[] colors;
    private int viewSize = 0;

    public ColorArrayAdapter(Context context, Integer[] colors, int viewSize) {
        super(context, R.layout.list_item, colors);
        this.colors = colors;
        this.viewSize = viewSize;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        return createColorImage(position);
    }

    @Override
    public View getDropDownView(int position, View convertView, ViewGroup parent) {
        return  createColorImage(position);
    }

    private ImageView createColorImage(int position){
        ImageView imageView = new ImageView(getContext());
        imageView.setBackgroundColor(colors[position]);

        AbsListView.LayoutParams params = new AbsListView.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.height =  viewSize;
        params.width =  viewSize;

        imageView.setLayoutParams(params);
        return imageView;
    }
}