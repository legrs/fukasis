repositoryのディレクトリ構成を以下に示します。
```
fukasis/
    ├── app/
    │   ├── app/
    │   ├── README.md
    ├── docs/
    │   ├── imgs/
    │   ├── kwsk.md
    ├── hardware/
    │   ├── cad/
    │   │   └── main.FCStd
    │   └── stl/
    ├── LICENSE
    └── README.md
```


筐体のFreeCADデータ(.FCStd)とFUKASIS-appのソースコードは、それぞれ本repositoryの`app/`と`hardware/cad/`ディレクトリに格納されています。

`fukasis/app/README.md`にはアプリの内部の説明がちょっとだけあります．


## アプリのbuild
Android Studioが必須です．

`クローンしたfukasis/app/local.properties`
この中身を以下のようにしてください．
(pathは環境に依存します．自分の環境でのpathを調べて置き換えてください)
(Windowsの場合はスラッシュもしくはバックスラッシュ2つです．)

```
sdk.dir=Android/Sdkのパス
org.gradle.java.home=jbrのパス
opencv.dir=このrepositoryをcloneしたパス/app/app/src/main/sdk/native/jni
```

これでbuildできるようになるはずです．

## 筐体
このFreeCADのプロジェクトは，残念ながら依存関係もごちゃごちゃしていてあまり良い作りではありません．

ちょっとしたCutぐらいなら問題ないと思いますが，しっかりした編集をするなら，
「これを参考にして作り直す」というのが一番近道になるかもれません．
