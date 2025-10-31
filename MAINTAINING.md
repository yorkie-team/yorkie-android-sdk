# Maintaining Yorkie

## Releasing a New Version

### Updating and Deploying Yorkie

1. Update VERSION_NAME in [gradle.properties](https://github.com/yorkie-team/yorkie-android-sdk/blob/main/gradle.properties#L16).
2. Create Pull Request and merge it into main.
3. Create [a new release](https://github.com/yorkie-team/yorkie-android-sdk/releases/new) by attaching the changelog by clicking `Generate release notes` button.
4. Then [GitHub action](https://github.com/yorkie-team/yorkie-android-sdk/blob/main/.github/workflows/publish.yml) will publish Yorkie as a artifact to [Maven Central Repository](https://central.sonatype.com/publishing).
5. Go to [deployments](https://central.sonatype.com/publishing), and **Publish** the artifact to synchronize with [Repository](https://repo1.maven.org/maven2/dev/yorkie/yorkie-android/0.6.25/).
