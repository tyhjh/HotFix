title: Android热修复原理及实现（一）
toc: true
categories: Android
tags: [热修复,插件化,反射]
cover: /cover/hotfix1-cover.png
thumbnail: /cover/hotfix1-cover.png
comments: true
excerpt: 自己之前也做过插件化换肤，涉及到的是插件资源文件的加载；最近看到同事培训的插件化涉及到具体代码的加载；想自己了解一下，就先从最常用的热修复开始看起，由于刚开始接触相关的概念，理解也不是很深，但是总体看下来还是比较简单的，这里记录一下自己的理解...
---

## 前言
自己之前也做过插件化换肤，涉及到的是插件资源文件的加载；最近看到同事培训的插件化涉及到具体代码的加载；想自己了解一下，就先从最常用的热修复开始看起，由于刚开始接触相关的概念，理解也不是很深，但是总体看下来还是比较简单的，这里记录一下自己的理解；


## 热修复的应用场景
热修复就是在APP上线以后，如果突然发现有缺陷了，如果重新走发布流程可能时间比较长，重新安装APP用户体验也不会太好；热修复就是通过发布一个插件，使APP运行的时候加载插件里面的代码，从而解决缺陷，并且对于用户来说是无感的（用户也可能需要重启一下APP）。


## 热修复的原理
先说结论吧，就是将补丁 dex 文件放到 dexElements 数组靠前位置，这样在加载 class 时，优先找到补丁包中的 dex 文件，加载到 class 之后就不再寻找，从而原来的 apk 文件中同名的类就不会再使用，从而达到修复的目的

理解这个原理，需要了解一下Android的代码加载的机制；

### Android运行流程
简单来讲整体流程是这样的：
1、Android程序编译的时候，会将.java文件编译时.class文件
2、然后将.class文件打包为.dex文件
3、然后Android程序运行的时候，Android的Dalvik/ART虚拟机就加载.dex文件
4、加载其中的.class文件到内存中来使用

### 类加载器
负责加载这些.class文件的就是类加载器（ClassLoader），APP启动的时候，会创建一个自己的ClassLoader实例，我们可以通过下面的代码拿到当前的ClassLoader
```java
ClassLoader classLoader = getClassLoader();
Log.i(TAG, "[onCreate] classLoader" + ":" + classLoader.toString());
```

ClassLoader加载类的方法就是loadClass可以看一下源码，是通过双亲委派模型（Parents Delegation Model），它首先不会自己去尝试加载这个类， 而是把这个请求委派给父类加载器去完成，当父加载器反馈自己无法完成这个加载请求（它的搜索范围中没有找到所需的类） 时， 子加载器才会尝试自己去完成加载，最后是调用自己的findClass方法完成的
```java
    protected Class<?> loadClass(String name, boolean resolve)
        throws ClassNotFoundException
    {
            // First, check if the class has already been loaded
            Class<?> c = findLoadedClass(name);
            if (c == null) {
                try {
                    if (parent != null) {
                        c = parent.loadClass(name, false);
                    } else {
                        c = findBootstrapClassOrNull(name);
                    }
                } catch (ClassNotFoundException e) {
                    // ClassNotFoundException thrown if class not found
                    // from the non-null parent class loader
                }

                if (c == null) {
                    // If still not found, then invoke findClass in order
                    // to find the class.
                    c = findClass(name);
                }
            }
            return c;
    }
```

ClassLoader是一个抽象类，通过打印可以看出来当前的ClassLoader是一个PathClassLoader；看一下PathClassLoader的构造函数，可以看出，需要传入一个dexPath也就是dex包的路径，和父类加载器；
```java
    //dexPath 包含 dex 的 jar 文件或 apk 文件的路径集，多个以文件分隔符分隔，默认是“：”
    public PathClassLoader(String dexPath, ClassLoader parent) {
        super((String)null, (File)null, (String)null, (ClassLoader)null);
        throw new RuntimeException("Stub!");
    }
```

PathClassLoader是BaseDexClassLoader的子类，除此之外BaseDexClassLoader还有一个子类是DexClassLoader，optimizedDirectory用来缓存优化的 dex 文件的路径，即从 apk 或 jar 文件中提取出来的 dex 文件；
```java
    public DexClassLoader(String dexPath, String optimizedDirectory, String librarySearchPath, ClassLoader parent) {
        super((String)null, (File)null, (String)null, (ClassLoader)null);
        throw new RuntimeException("Stub!");
    }
```

这两个的区别，网上的答案是

> 1、DexClassLoader可以加载jar/apk/dex，可以从SD卡中加载未安装的apk
2、PathClassLoader只能加载系统中已经安装过的apk

从这个答案可以知道，我们想要加载更新的插件，肯定是使用 DexClassLoader；但是有点离谱的是其实我用两个都能成功，也许我加载的插件包名这些都和原APP一致导致的吧。


### 类加载器的运行流程
具体的实现都在BaseDexClassLoader里面，看一下里面的实现（源码看不了，网上搜一下），下面是一个构造方法
```java
public BaseDexClassLoader(String dexPath, File optimizedDirectory, String libraryPath, ClassLoader parent) {
    super(parent);
    this.originalPath = dexPath;
    this.pathList = new DexPathList(this, dexPath, libraryPath, optimizedDirectory);
}
```
构造方法创建了一个DexPathLis，里面解析了dex文件的路径，并将解析的dex文件都存在this.dexElements里面
```java

public DexPathList(ClassLoader definingContext, String dexPath, String libraryPath, File optimizedDirectory) {
…
    //将解析的dex文件都存在this.dexElements里面
    this.dexElements = makeDexElements(splitDexPath(dexPath), optimizedDirectory);
}
 
 //解析dex文件
private static Element[] makeDexElements(ArrayList<File> files, File optimizedDirectory) {
    ArrayList<Element> elements = new ArrayList<Element>();
    for (File file : files) {
        ZipFile zip = null;
        DexFile dex = null;
        String name = file.getName();
        if (name.endsWith(DEX_SUFFIX)) {
            dex = loadDexFile(file, optimizedDirectory);
        } else if (name.endsWith(APK_SUFFIX) || name.endsWith(JAR_SUFFIX) || name.endsWith(ZIP_SUFFIX)) {
            zip = new ZipFile(file);
        }
        ……
        if ((zip != null) || (dex != null)) {
            elements.add(new Element(file, zip, dex));
        }
    } return elements.toArray(new Element[elements.size()]);
}
```

然后我们再回头看一下ClassLoader加载类的方法,就是loadClass()，最后调用findClass方法完成的;BaseDexClassLoader 重写了该方法，如下
```java
 @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        List<Throwable> suppressedExceptions = new ArrayList<Throwable>();
        // 使用pathList对象查找name类
        Class c = pathList.findClass(name, suppressedExceptions);
        return c;
    }
```
最终是调用 pathList的findClass方法，看一下方法如下
```java
public Class findClass(String name, List<Throwable> suppressed) {
    // 遍历从dexPath查询到的dex和资源Element
    for (Element element : dexElements) {
        DexFile dex = element.dexFile;
        // 如果当前的Element是dex文件元素
        if (dex != null) {
            // 使用DexFile.loadClassBinaryName加载类
            Class clazz = dex.loadClassBinaryName(name, definingContext, suppressed);
            if (clazz != null) {
                return clazz;
            }
        }
    }
    if (dexElementsSuppressedExceptions != null) {
        suppressed.addAll(Arrays.asList(dexElementsSuppressedExceptions));
    }
    return null;
}
```
### 结论
所以整个类加载流程就是

> 1、类加载器BaseDexClassLoader先将dex文件解析放到pathList到dexElements里面
2、加载类的时候从dexElements里面去遍历，看哪个dex里面有这个类就去加载，生成class对象

所以我们可以将自己的dex文件加载到dexElements里面，并且放在前面，加载的时候就可以加载我们插件中的类，不会加载后面的,从而替换掉原来的class。


## 热修复的实现
知道了原理，实现就比较简单了，就添加新的dex对象到当前APP的ClassLoader对象（也就是BaseDexClassLoader）的pathList里面的dexElements；要添加就要先创建，我们使用DexClassLoader先加载插件，先生成插件的dexElements，然后再添加就好了。

当然整个过程需要使用反射来实现。除此以外，常用的两种方法是使用apk作为插件和使用dex文件作为插件；下面的两个实现都是对程序中的一个方法进行了修改，然后分别打了 dex包和apk包，程序运行起来执行的方法就是插件里面的方法而不是程序本身的方法；

### dex插件
对于dex文件作为插件，和之前说的流程完全一致，先将修改了的类进行打包成dex包，将dex进行加载，插入到dexElements集合的前面即可；打包流程是先将.java文件编译成.class文件，然后使用SDK工具打包成dex文件人，然后APP下载，加载即可；

#### dex打包工具
d8 作为独立工具纳入了 Android 构建工具 28.0.1 及更高版本中：`C:\Users\hanpei\AppData\Local\Android\Sdk\build-tools\29.0.2\d8.bat`；输入字节码可以是 *.class 文件或容器（例如 JAR、APK 或 ZIP 文件）的任意组合。您还可以添加 DEX 文件作为 d8 的输入，以将这些文件合并到 DEX 输出中

```java
 d8 MyProject/app/build/intermediates/classes/debug/*/*.class
```

#### 具体的代码实现
代码的注释已经很详细了，就不再进行说明了
```java
//在Application中进行替换
public class MApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        //dex作为插件进行加载
        dexPlugin();
    }
    ...

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
```

### apk插件
apk作为插件，就是我们重新打了一个新的apk包作为插件，打包很简单方便，缺点就是文件大；使用apk的话就没必要是将dex插入dexElements里面去，直接将之前的dexElements替换就可以了；

#### 具体的实现
代码的注释已经很详细了，就不再进行说明了
```java
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
```

## 总结
思路还是很清晰的，主要是要先了解类加载的原理，整体来讲还是比较简单的；采用类加载方案的主要是以腾讯系为主，包括微信的Tinker、QQ空间的超级补丁、手机QQ的QFix、饿了么的Amigo和Nuwa等等；也有一些其他的方法来实现热修复，有空再进行总结分享。


<article class="message is-info">
  <div class="message-body">
    项目源码：https://github.com/tyhjh/HotFix
  </div>
</article>