# SPOTMY

Player de música local para Android, inspirado em padrões modernos de navegação e reprodução — sem streaming obrigatório e sem depender de uma conta Spotify.

## Objetivos

- Ler arquivos de áudio do dispositivo com permissão do Android.
- Busca na biblioteca local.
- Reprodução em segundo plano com Media3.
- Playlists locais e músicas favoritas.
- Cápsula do Tempo com estatísticas calculadas a partir de sessões de reprodução reais.
- Interface escura com identidade própria do SPOTMY.

## Estado atual

A primeira base está no repositório: Kotlin, Jetpack Compose, Media3, listagem de arquivos de áudio, busca e reprodução básica. Playlists persistentes e estatísticas reais ainda precisam ser implementadas. O GitHub Actions tenta compilar um APK de depuração automaticamente a cada atualização.

## Referências estudadas

- [Spotify Mobius](https://github.com/spotify/mobius): separação entre estado, eventos e efeitos. Usamos a ideia arquitetural como referência; Mobius não é uma dependência do app.
- [Spotify Android SDK](https://github.com/spotify/android-sdk): integra apps ao serviço Spotify. Não é necessário para tocar arquivos locais.
- [PixelPlayerOSS](https://github.com/PixelPlayerHQ/PixelPlayerOSS): projeto aberto de player offline para Android, útil como referência de stack (Compose, Media3 e Material 3). O SPOTMY não copia sua implementação.

## Gerar o APK

1. Abra a aba **Actions** deste repositório.
2. Entre na execução mais recente de **Android APK**.
3. Quando a execução terminar com sucesso, baixe o artefato `SPOTMY-debug-apk`.

## Privacidade

A biblioteca é consultada localmente no aparelho. Esta base não envia seus arquivos de áudio a um servidor.