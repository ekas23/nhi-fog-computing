# The NHI package for iFogSim2

iFogSim2 is a separate project (MIT, CLOUDS Laboratory, University of
Melbourne) and is **not vendored in this repository**. Only the new
`org.fog.nhi` package is committed here.

## Applying it

```bash
git clone https://github.com/Cloudslab/iFogSim2.git
cp -r java/org/fog/nhi iFogSim2/src/org/fog/

cd iFogSim2
CP=$(find jars -name '*.jar' | tr '\n' ':')
javac -nowarn -cp "$CP" -d out/build $(find src -name '*.java')
```

iFogSim2 vendors its dependency jars, so no Maven is needed — plain `javac`
compiles all 445 stock files plus this package.

## Also required

Copy the SUMO traces into iFogSim2's dataset directory:

```bash
cp sumo/sumo_*.csv iFogSim2/dataset/
```

## Note on stock sources

No iFogSim2 file is modified. `NHIController` subclasses `Controller` to
suppress its `System.exit(0)` call, and `NetworkUsageMonitor` is reset via
reflection. Both are documented in `docs/RESULTS.md` §5.
