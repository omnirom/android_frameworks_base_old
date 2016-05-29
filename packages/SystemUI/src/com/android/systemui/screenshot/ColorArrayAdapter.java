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
import android.widget.ImageView.ScaleType;
import android.widget.TextView;

import com.android.systemui.R;

/**
 * Created by DanielHuber on 16.04.2016.
 */
public class ColorArrayAdapter extends ArrayAdapter<Integer> {
    private Integer[] colors;

    public ColorArrayAdapter(Context context, Integer[] colors) {
        super(context, R.layout.list_item, colors);
        this.colors = colors;
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
        imageView.setScaleType(ScaleType.CENTER_INSIDE);
        int width = getContext().getResources().getDimensionPixelSize(R.dimen.crop_buttons);
        AbsListView.LayoutParams params = new AbsListView.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.height =  width;
        params.width =  width;
        imageView.setLayoutParams(params);
        return imageView;
    }
}
