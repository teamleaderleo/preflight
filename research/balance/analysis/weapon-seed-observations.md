# First weapon seed observations

This note uses the six-item 0.98a weapon seed only. It is a feature-design and falsification pass, not a weapon power model.

## The naive table already fails in useful ways

| Weapon | Community tier | OP | Range | DPS | DPS/OP | Flux/DPS | Single-hit or rocket damage |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Light Assault Gun | C | 5 | 700 | 160 | **32.0** | 1.00 | 40 HE |
| Light Needler | A+ | 8 | 700 | 150 | 18.8 | 0.80 | 50 kinetic × 15 burst |
| Antimatter Blaster | A- / S | 9 | 400 | 137 | 15.2 | 1.07 | **1400 energy** |
| LR PD Laser | D | 4 | 800 | 50 | 12.5 | **0.60** | beam |
| Harpoon MRM | A+ | 4 | 2500 | finite ammo | — | 0 | **750 HE** |
| Annihilator Rocket Launcher | C+ / B+ | 4 | 1500 | 172 | **43.0** | 0 | 200 HE × 5 salvo |

A model built from DPS/OP, range/OP, and flux efficiency would badly misorder several of these.

That is the desired outcome of the seed. The first feature/model design should explain these failures rather than smoothing them away.

## Case 1: Light Assault Gun versus Light Needler

The Light Assault Gun has higher sustained DPS and much better nominal DPS/OP than the Light Needler.

The human ranking goes the other direction by several tiers.

The key difference is downstream mechanics:

- LAG's 40-damage HE projectiles lose much of their nominal anti-armor value to armor damage reduction;
- Light Needler's kinetic burst is applied to shields, where the intended damage-type advantage is immediately useful;
- the Needler delivers the pressure in a short burst, pushing targets rapidly into high flux;
- high-flux state changes the opponent AI's tactical behavior and creates kill opportunities for the rest of the fleet.

Therefore weapon modeling needs separate representations for:

```text
nominal sustained output
per-hit armor conversion
shield pressure
burst pressure / pressure slope
state transitions induced in target AI
allied kill conversion after the pressure event
```

`damage type × DPS` is still too crude because hit strength and timing matter independently.

## Case 2: Antimatter Blaster demonstrates why range is nonlinear

AMB has only 400 range and mediocre sustained DPS for 9 OP, yet the source rates it `A- / S`.

The same short range that looks like a static penalty can improve AI execution: the AI is less likely to spend a high-value charge on a speculative distant shot. When it fires, the 1400-damage projectile is accurate enough to create an overload or meaningful armor breach.

The player rating rises further because a human chooses the target and timing of the spike.

Range therefore needs at least two downstream effects:

```text
access probability / time in range
fire-discipline effect given weapon role and AI
```

More range is not monotonically more value for every finite-ammo/high-alpha weapon.

AMB is also a useful test for **Control Premium** because the factual weapon record is identical while targeting/timing policy changes.

## Case 3: LR PD Laser proves range efficiency is not enough

LR PD has:

- 800 range;
- perfect beam accuracy;
- 0.6 flux/damage;
- only 4 OP.

Those are superficially attractive numbers. The human tier is D.

The main complaint is opportunity cost: one or a few LR PD lasers do too little interception work per slot/OP, while other PD configurations produce much more useful stopping power.

The dissent is equally valuable: some players like LR PD on cruisers/capitals because overlapping long-range coverage can protect nearby ships.

That means PD needs distinct measures for:

```text
self-protection interception
allied interception / coverage radius
burst interception capacity
saturation resistance
fighter damage
mount/OP opportunity cost
number and orientation of host mounts
fleet formation overlap
```

This is a textbook **low Power / potentially nonzero niche Breadth cell** case depending on host/fleet policy.

## Case 4: Harpoon demonstrates AI-role metadata as real combat value

Harpoon has finite ammo and no conventional sustained-DPS number. Yet it receives A+.

The important facts include:

- long reach;
- homing;
- HE payload;
- the weapon's `Finisher` role / hints;
- AI behavior that tends to hold the missile for downed or high-flux shields.

The value is not simply `750 damage × 3 ammo`.

The downstream event is closer to:

```text
ally creates high-flux/overload state
  -> Harpoon recognizes conversion opportunity
  -> missile reaches target
  -> exposed hull/armor takes HE spike
  -> target leaves battle sooner
  -> friendly fleet retargets sooner
```

A balance model should eventually measure **kill conversion** and **time-to-remove-disabled/high-flux target**, not only personal damage dealt.

The general weapon catalog's primary-role metadata is therefore model input because it can change AI firing policy.

## Case 5: Annihilator shows theoretical value versus realized hit geometry

Annihilator's static numbers look absurd for 4 OP:

- 172 nominal DPS;
- zero flux;
- 50 rockets;
- 200 HE damage per rocket;
- five-round salvos.

Yet the source rates AI C+ and player B+.

The reason is realization. The launcher has no tracking and its spread pattern rewards firing at close range. The AI treats it as pressure and may fire from the edge of its nominal 1500 range, where fewer rockets connect.

So the useful quantity is something like:

```text
realized damage = nominal payload × hit probability(distance, target size, relative motion, spread, firing policy)
```

For finite ammunition, wasted salvos also create an endurance cost that cannot be recovered later.

This is a clean control-policy experiment because the player can deliberately close before firing.

## Cross-seed feature families

The six weapons already justify these independent feature families:

### Damage realization

- damage type;
- per-hit damage;
- burst composition;
- armor conversion;
- shield conversion;
- tracking/accuracy;
- target-size sensitivity;
- distance-dependent hit probability.

### Pressure timing

- sustained pressure;
- burst pressure;
- time to raise target flux by selected thresholds;
- overload probability;
- target AI response after pressure thresholds.

### Resource/endurance

- OP;
- own flux;
- finite ammo/charges;
- reload/regeneration;
- useful damage before depletion;
- wasted-ammo rate.

### AI execution

- primary role/hints;
- target selection;
- firing range actually chosen;
- reserve/finisher behavior;
- overkill;
- willingness to fire into poor armor/shield states.

### Fleet externalities

- kill conversion;
- allied pressure amplification;
- PD coverage for allies;
- target displacement/backpedaling;
- time freed for allies after a target dies.

### Host compatibility

- slot/mount type;
- host flux headroom;
- mobility;
- range doctrine;
- number/orientation of mounts;
- enabling hullmods/skills.

## First useful automated weapon experiments

A full fleet tournament is unnecessary for every early metric. Some mechanics can be isolated cheaply before the complete eDP fit:

1. **Armor conversion curve:** replay each projectile/hit size against a representative armor grid and retain effective armor/hull damage.
2. **Shield-pressure curve:** measure time/flux cost to move standard targets through 25/50/75/90% flux under realistic firing cadence.
3. **Hit-realization sweep:** distance × target size × lateral velocity × weapon tracking/spread.
4. **Finite-ammo policy:** AI versus scripted/player-like firing policy, retaining useful hits and ammo wasted.
5. **PD saturation:** missile/fighter arrival density versus interception probability, both self-only and overlapping allied coverage.
6. **Kill-conversion test:** introduce a target at controlled high-flux/overloaded states and measure how quickly a finisher converts it into a kill.

Those outputs become explanatory features. Fleet-level combat still decides whether the feature actually buys eDP.

## Early sanity checks for a fitted model

Before trusting any absolute Power score, require the fitted model to explain or reproduce these qualitative relationships under the intended policy:

```text
Light Needler > Light Assault Gun for general small-ballistic value
AMB player > AMB AI
Annihilator player > Annihilator AI
Harpoon has high AI value despite finite ammo and no sustained-DPS statistic
LR PD's long range does not automatically rescue its ordinary general-use score
```

If the model misses one, inspect the missing mechanic instead of forcing the label with an arbitrary tier coefficient.
