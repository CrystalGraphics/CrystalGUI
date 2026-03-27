# VERY IMPORTANT
During development, use the `Run Client (Java 25, hotswap)` task <br>


## Shadowed libraries
Shadowed libraries will also get downgraded to Java 8. 


**DO NOT** use libraries that rely on JNI *unless* their natives were compiled against Java 8.
<br>If the natives were compiled against a higher version of the Java API, there will be major problems.
<br>(Recompiling shouldn't be too big of an issue if the project is OpenSource)
