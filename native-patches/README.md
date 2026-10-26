# mqvpn ネイティブ差分

`app/src/main/jniLibs/arm64-v8a/libmqvpn_jni.so` は、mqvpn v0.18.0
（commit `14e98987b3d5cbdc25c114923db1114996f7aa1a`）へ
`mqvpn-v0.18.0-path-rate-limit.patch` を適用してビルドしています。

差分は経路単位の送受信レート上限をネイティブ POSIX bind と Android
reactor に追加します。受信側は上限に達した UDP datagram をソケットの
受信キューに残して、トークン補充後に reactor が読み込みます。

ビルド条件:

- Android NDK 28.2.13676358
- API level 26
- arm64-v8a
- mqvpn 公式 `scripts/build_android.sh` 相当の CMake クロスビルド
- `:sdk-native:assembleRelease` の strip 済み `libmqvpn_jni.so`
