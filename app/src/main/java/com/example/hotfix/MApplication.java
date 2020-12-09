package com.example.hotfix;

import android.app.Application;
import android.util.Log;
import java.io.File;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import dalvik.system.BaseDexClassLoader;
import dalvik.system.DexClassLoader;
import dalvik.system.PathClassLoader;

public class MApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        //dex作为插件进行加载
        dexPlugin();
    }

    /**
     * apk作为插件加载
     */
    private void apkPlugin() {
        //插件包文件
        File file = new File("/sdcard/FixTest.apk");
        if (!file.exists()) {
            Log.i("MApplication", "插件包不在");
            return;
        }
        try {
            //获取到 BaseDexClassLoader 的  pathList字段
            // private final DexPathList pathList;
            Field pathListField = BaseDexClassLoader.class.getDeclaredField("pathList");
            //破坏封装，设置为可以调用
            pathListField.setAccessible(true);
            //拿到当前ClassLoader的pathList对象
            Object pathListObj = pathListField.get(getClassLoader());

            //获取当前ClassLoader的pathList对象的字节码文件（DexPathList ）
            Class<?> dexPathListClass = pathListObj.getClass();
            //拿到DexPathList 的 dexElements字段
            // private final Element[] dexElements；
            Field dexElementsField = dexPathListClass.getDeclaredField("dexElements");
            //破坏封装，设置为可以调用
            dexElementsField.setAccessible(true);

            //使用插件创建 ClassLoader
            DexClassLoader pathClassLoader = new DexClassLoader(file.getPath(), getCacheDir().getAbsolutePath(), null, getClassLoader());
            //拿到插件的DexClassLoader 的 pathList对象
            Object newPathListObj = pathListField.get(pathClassLoader);
            //拿到插件的pathList对象的 dexElements变量
            Object newDexElementsObj = dexElementsField.get(newPathListObj);
            //将插件的 dexElements对象设置给 当前ClassLoader的pathList对象
            dexElementsField.set(pathListObj, newDexElementsObj);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * dex作为插件加载
     */
    private void dexPlugin(){
        //插件包文件
        File file = new File("/sdcard/FixTest.dex");
        if (!file.exists()) {
            Log.i("MApplication", "插件包不在");
            return;
        }
        try {
            //获取到 BaseDexClassLoader 的  pathList字段
            // private final DexPathList pathList;
            Field pathListField = BaseDexClassLoader.class.getDeclaredField("pathList");
            //破坏封装，设置为可以调用
            pathListField.setAccessible(true);
            //拿到当前ClassLoader的pathList对象
            Object pathListObj = pathListField.get(getClassLoader());

            //获取当前ClassLoader的pathList对象的字节码文件（DexPathList ）
            Class<?> dexPathListClass = pathListObj.getClass();
            //拿到DexPathList 的 dexElements字段
            // private final Element[] dexElements；
            Field dexElementsField = dexPathListClass.getDeclaredField("dexElements");
            //破坏封装，设置为可以调用
            dexElementsField.setAccessible(true);

            //使用插件创建 ClassLoader
            DexClassLoader pathClassLoader = new DexClassLoader(file.getPath(), getCacheDir().getAbsolutePath(), null, getClassLoader());
            //拿到插件的DexClassLoader 的 pathList对象
            Object newPathListObj = pathListField.get(pathClassLoader);
            //拿到插件的pathList对象的 dexElements变量
            Object newDexElementsObj = dexElementsField.get(newPathListObj);

            //拿到当前的pathList对象的 dexElements变量
            Object dexElementsObj=dexElementsField.get(pathListObj);

            int oldLength = Array.getLength(dexElementsObj);
            int newLength = Array.getLength(newDexElementsObj);
            //创建一个dexElements对象
            Object concatDexElementsObject = Array.newInstance(dexElementsObj.getClass().getComponentType(), oldLength + newLength);
            //先添加新的dex添加到dexElement
            for (int i = 0; i < newLength; i++) {
                Array.set(concatDexElementsObject, i, Array.get(newDexElementsObj, i));
            }
            //再添加之前的dex添加到dexElement
            for (int i = 0; i < oldLength; i++) {
                Array.set(concatDexElementsObject, newLength + i, Array.get(dexElementsObj, i));
            }
            //将组建出来的对象设置给 当前ClassLoader的pathList对象
            dexElementsField.set(pathListObj, concatDexElementsObject);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

}
