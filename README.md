# SPOTMY

Player de música local para Android, inspirado em padrões modernos de navegação e reprodução — sem streaming obrigatório e sem depender da conta Spotify.

## Objetivos

- Ler arquivos de áudio do dispositivo com permissão do Android.
- Busca na biblioteca local.
- Reprodução em segundo plano com controles de notificação e tela bloqueada.
- Playlists locais e músicas favoritas.
- Cápsula do Tempo com estatísticas calculadas a partir de sessões de reprodução reais.
- Interface escura, com identidade própria do SPOTMY.

## Estado do projeto

Primeira base Android em Kotlin + Jetpack Compose. Recursos de playlists persistentes e estatísticas completas serão implementados por etapas. O APK é compilado pelo GitHub Actions.

## Referências estudadas

- [Spotify Mobius](https://github.com/spotify/mobius): separação entre estado, eventos e efeitos. Referência arquitetural; não é dependência obrigatória.
- [Spotify Android SDK](https://github.com/spotify/android-sdk): voltado à integração com o serviço Spotify; não é necessário para tocar arquivos locais.
- [PixelPlayer](https://github.com/theovilardo/PixelPlayer): referência comunitária para padrões de app de música Android. O SPOTMY mantém implementação e identidade próprias.

## Build

Abra o repositório no GitHub e acesse **Actions → Android APK**. Ao concluir, baixe o artefato `SPOTMY-debug-apk`.

## Privacidade

A biblioteca é lida no aparelho. A proposta não envia os arquivos de áudio a um servidor.