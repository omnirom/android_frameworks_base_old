package com.android.systemui.omni.xFallView.models;


import android.graphics.Bitmap;


public class XViewModel {

    private Bitmap bitmap;

    private float posX;
    private float posY;

    private float pivotX;
    private float pivotY;

    private float speedY;

    private float scale;

    private int rotateAngle;

    private int transparency;

    public XViewModel(Bitmap bitmap, float posX, float posY, float pivotX, float pivotY, float speedY) {
        this.bitmap = bitmap;
        this.posX = posX;
        this.posY = posY;
        this.pivotX = pivotX;
        this.pivotY = pivotY;
        this.speedY = speedY;
    }

    public Bitmap getBitmap() {
        return bitmap;
    }

    public float getPosX() {
        return posX;
    }

    public void setPosX(float posX) {
        this.posX = posX;
    }

    public float getPosY() {
        return posY;
    }

    public void setPosY(float posY) {
        this.posY = posY;
    }

    public float getPivotX() {
        return pivotX;
    }

    public void setPivotX(float pivotX) {
        this.pivotX = pivotX;
    }

    public float getPivotY() {
        return pivotY;
    }

    public void setPivotY(float pivotY) {
        this.pivotY = pivotY;
    }

    public float getSpeedY() {
        return speedY;
    }

    public void setSpeedY(float speedY) {
        this.speedY = speedY;
    }

    public float getScale() {
        return scale;
    }

    public void setScale(float scale) {
        this.scale = scale;
    }

    public int getRotateAngle() {
        return rotateAngle;
    }

    public void setRotateAngle(int rotateAngle) {
        this.rotateAngle = rotateAngle;
    }

    public int getTransparency() {
        return transparency;
    }

    public void setTransparency(int transparency) {
        this.transparency = transparency;
    }

}
