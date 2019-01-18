# Release process

- Update build.sc version (follow SEMVER)
- Make a commit ("Set to version 0.6.6")
- Make a tag ("v0.6.6")
- Run the below (may require multiple attempts)
```
mill mill.scalalib.PublishModule/publishAll \ 
  --sonatypeCreds $SONATYPE_USERNAME:$SONATYPE_PASSWORD \ 
  --gpgPassphrase $GPG_PASSPHRASE \ 
  --publishArtifacts \
  __.publishArtifacts
```
- Login to Sonatype (https://oss.sonatype.org/)
- Go to Staging Repositories
- Find the repository (usually something like compermutive-####)
- Check contents of repository (see all jars are there)
- Close repository
- Release repository (select auto-drop)
- Check https://mvnrepository.com/artifact/com.permutive/fs2-google-pubsub in a while
- You're done!


