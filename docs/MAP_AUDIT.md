# Map audit (27 Nov 2026)

Automatic check of every placed object against the map's 1 m land mask (`tools/audit/`). World coordinates are metres east / north of the Campanile; open the full-screen map and use them to find each spot.

## Fixed

* **16 bridges removed** that stood on pavement with no water within 6 m (OSM ways tagged as bridges over filled-in canals, passages, or misplaced):
  * line 10 `noparapet` at (-190, -6857)
  * line 81 `irongate` at (-1684, 577)
  * line 122 `stucco_s` at (-803, -1151)
  * line 221 `irongate` at (2609, -1712)
  * line 441 `noparapet` at (3031, -1579)
  * line 442 `brick_l` at (2916, -1768)
  * line 443 `irongate` at (2708, -1781)
  * line 444 `brick_l` at (2774, -1829)
  * line 445 `irongate` at (2970, -1679)
  * line 703 `brick_l` at (-1944, 754)
  * line 853 `balustrade` at (3544, 2551)
  * line 861 `stucco_s` at (3729, 2635)
  * line 862 `stucco` at (3792, 2632)
  * line 864 `brick` at (5164, 2533)
  * line 869 `brick` at (5606, 2835)
  * line 874 `stucco_s` at (6349, 3795)
* **7 vaporetto gangways removed** that OSM tags as bridges (the game already builds the gangway, so a stone bridge was drawn on top of it):
  * line 43 `noparapet` at Santa Marta
  * line 80 `stucco_s` at Piazzale Roma Sant'Andrea B
  * line 173 `irongate_l` at Ca' Rezzonico
  * line 829 `stucco_s` at Fondamente Nove C
  * line 830 `stucco_s` at Fondamente Nove C
  * line 831 `stucco_s` at Fondamente Nove B
  * line 832 `stucco_s` at Fondamente Nove B
* **103 bridges lengthened in place** (same position and angle) because a staircase ended in the water instead of on the fondamenta; median +20 %, never more than +60 %.
* **Street furniture in canals:** lamps, benches, bins, wells, fountains and trees that OSM puts in the water are no longer built (except on piers and pontoon decks). The audit found 41 lamps, 49 trees, 10 bins, 4 benches and 2 wells/fountains in the water.
* Mooring piles on land (the poles in the pavement by Rialto) and missing pier collisions were fixed in PR #1.

## Needs a look in the game

Not changed automatically - the right fix needs eyes (or a new bridge model):

* line 2 `stucco_s` at (-723, -5778): far too short for the canal it crosses (needs a longer bridge type)
* line 42 `balustrade_l` at (-1608, -695): runs along the water instead of across it
* line 186 `stucco` at (-1256, 451): far too short for the canal it crosses (needs a longer bridge type)
* line 216 `castiron` at (2212, -2892): centre on the fondamenta next to a canal (shifted off its canal?)
* line 246 `irongate_l` at (-681, 79): centre on the fondamenta next to a canal (shifted off its canal?)
* line 270 `stucco_s` at (-451, 601): far too short for the canal it crosses (needs a longer bridge type)
* line 273 `stucco_s` at (-443, 603): far too short for the canal it crosses (needs a longer bridge type)
* line 293 `stucco_s` at (32, 408): far too short for the canal it crosses (needs a longer bridge type)
* line 294 `brick` at (14, 411): far too short for the canal it crosses (needs a longer bridge type)
* line 322 `stucco` at (-61, 96): centre on the fondamenta next to a canal (shifted off its canal?)
* line 352 `balustrade_l` at (137, 271): centre on the fondamenta next to a canal (shifted off its canal?)
* line 376 `castiron` at (562, 610): centre on the fondamenta next to a canal (shifted off its canal?)
* line 432 `balustrade` at (852, 92): centre on the fondamenta next to a canal (shifted off its canal?)
* line 446 `irongate` at (2921, -1337): centre on the fondamenta next to a canal (shifted off its canal?)
* line 447 `stucco_s` at (2983, -1076): runs along the water instead of across it
* line 448 `stucco_s` at (2986, -1069): runs along the water instead of across it
* line 449 `irongate_l` at (3179, -737): centre on the fondamenta next to a canal (shifted off its canal?)
* line 657 `brick_l` at (-1426, 1081): centre on the fondamenta next to a canal (shifted off its canal?)
* line 738 `balustrade_l` at (-1324, 785): centre on the fondamenta next to a canal (shifted off its canal?)
* line 828 `irongate` at (379, 799): centre on the fondamenta next to a canal (shifted off its canal?)
* line 849 `brick` at (3081, 857): centre on the fondamenta next to a canal (shifted off its canal?)
* line 865 `stucco_s` at (5233, 2579): centre on the fondamenta next to a canal (shifted off its canal?)
* line 866 `stucco` at (5373, 2669): centre on the fondamenta next to a canal (shifted off its canal?)
* line 868 `stucco` at (5728, 2960): centre on the fondamenta next to a canal (shifted off its canal?)
* line 870 `noparapet` at (5840, 3087): centre on the fondamenta next to a canal (shifted off its canal?)
* line 871 `brick` at (6697, 3231): centre on the fondamenta next to a canal (shifted off its canal?)
* line 872 `stucco` at (6539, 2992): centre on the fondamenta next to a canal (shifted off its canal?)
* line 873 `stucco_s` at (6425, 2840): centre on the fondamenta next to a canal (shifted off its canal?)
* line 875 `irongate` at (7024, 1795): runs along the water instead of across it
* line 876 `brick` at (6787, 3874): centre on the fondamenta next to a canal (shifted off its canal?)
* line 877 `stucco_s` at (6942, 4271): centre on the fondamenta next to a canal (shifted off its canal?)

Other findings:

* 6 small 3 m 'buildings' on the water at the Lido (x 6700-7100) and one at (478, 1262): beach cabins / huts on sand the land mask does not include. Left as they are.
* The tallest building is 75 m at (753, 468), San Francesco della Vigna's bell tower (real height ~69 m). Fine.
* San Pietro di Castello pontoon is 32 m from the quay; its gangway reaches, but check it looks right.
* The Ponte della Libertà and the bus-terminal ramps (the `liberta`, `ribbon`, `girder` segment bridges at Piazzale Roma / Tronchetto) were left untouched as asked.
