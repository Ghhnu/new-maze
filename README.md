# MazeGen

Mod de Fabric (Minecraft 1.21.1) que genera laberintos aleatorios de piedra antigua con
el comando:

```
/generate maze <x> <y> <z>
/generate maze <x> <y> <z> coordenadas <sizeX> <sizeZ>
```

- La primera forma usa el tamaño por defecto (100x100 celdas).
- La segunda deja elegir el tamaño en celdas por lado (mínimo 5, máximo 200 en cada eje;
  X y Z pueden ser distintos, no hace falta que sea cuadrado). Ejemplo:
  `/generate maze ~ ~ ~ coordenadas 100 100`.

Requiere nivel de permisos de operador (op nivel 2). Funciona tanto en singleplayer
(servidor integrado) como en servidores dedicados, siempre que el mod esté instalado
en el servidor.

## Qué genera

- Laberinto "perfecto" (un único camino entre dos puntos cualquiera, sin bucles ni
  callejones redundantes) generado con backtracking aleatorio — **cada ejecución usa
  una semilla distinta**, así que ningún laberinto sale igual.
- **Pasillos de 3 bloques de ancho** y **paredes de 3 bloques de grosor** (incluido el
  muro perimetral exterior). Con celdas de tamaño N x M, el recinto real mide
  `N*6+3` x `M*6+3` bloques.
- Paredes: mezcla aleatoria de piedra, roca (cobblestone) y roca musgosa, de 4 bloques
  de alto.
- Antorchas de pared cada pocas celdas, en las caras de pared que dan a un pasillo.
- Enredaderas colocadas de forma puntual (~14% de probabilidad por tramo de pared,
  colgando 1-3 bloques desde el techo) para dar aspecto de ruina antigua sin tapar
  las paredes por completo.
- Suelo: mezcla de roca musgosa, bloque de musgo y **calcita** (ver nota abajo).
- Techo: cristal (vidrio) transparente sobre todo el recinto.
- Suelo de la celda de entrada: hormigón/concreto rojo.
- Suelo de la celda de salida (la celda de borde más lejana de la entrada dentro del
  propio laberinto): hormigón/concreto verde.
- Se abre un hueco de 3 bloques en la pared exterior tanto en la entrada como en la
  salida para poder entrar/salir caminando desde fuera.

## Spawners y cofres de botín

Repartidos con rareza por celdas interiores del laberinto (nunca en la entrada ni en
la salida), en una cantidad que escala con el tamaño del laberinto (más celdas, más
spawners y cofres, dentro de unos límites razonables para no saturarlo):

- **Spawners**: zombie, blaze y wither skeleton a partes iguales.
- **Cofres normales**: siempre llevan chuletas de cerdo cocinadas; 20% de probabilidad
  de llevar además espada de diamante + escudo; 50% de probabilidad (independiente) de
  llevar 2 lingotes de netherite.

## Cofre de la salida

En la celda de salida se coloca un cofre especial con botín "épico", todo completamente
encantado:

- 1 Elytra (Irrompibilidad III, Reparación).
- 3 Mazos/Mace (Densidad V, Ruptura IV, Ráfaga de Viento III, Irrompibilidad III,
  Reparación).
- 3 Tridentes (Impaler V, Lealtad III, Irrompibilidad III, Reparación).

## El laberinto cambia cada 30 segundos

Al estilo Maze Runner: una vez terminada la construcción, cada 30 segundos algunos
tramos de pasillo **interiores** del laberinto se abren o se cierran solos (se añaden o
quitan paredes entre celdas vecinas). La cantidad de tramos que cambian por ciclo
escala con el tamaño del laberinto.

- **La entrada y la salida nunca se mueven** ni quedan bloqueadas por un cambio.
- **El borde perimetral del laberinto nunca se toca** (así nadie se escapa por fuera
  ni queda un hueco en el muro exterior donde no debería haberlo).
- Solo se ven afectados los muros que separan celdas interiores entre sí.

## Nota sobre el "musgo pálido"

El bloque *Pale Moss Block* no existe en Minecraft 1.21.1 — se añadió en la
actualización 1.21.4 (bioma Pale Garden). Como el proyecto de referencia usa 1.21.1,
se sustituyó por **Calcita** (piedra clara) para conservar el contraste "musgo oscuro
/ piedra clara" en el suelo. Si en algún momento se sube el proyecto a 1.21.4+, basta
con cambiar `Blocks.CALCITE` por `Blocks.PALE_MOSS_BLOCK` en `MazeBuilder`.

## Rendimiento

Cada laberinto son varios cientos de miles (o millones, según el tamaño elegido) de
bloques. Para no congelar el servidor, la construcción y los retoques periódicos se
reparten en varios ticks (8000 bloques por tick) mediante una cola en
`MazeBuildQueue`, en vez de colocarlos todos de golpe en el mismo tick. Verás un
mensaje de progreso al lanzar el comando y otro cuando termine, con el tiempo total.

La cola soporta varios lotes a la vez (incluso de mundos distintos): si lanzas varios
`/generate maze` seguidos, o si varios laberintos están cambiando de sector al mismo
tiempo, todos los trabajos conviven en la misma cola y se procesan en orden de llegada.

## Estructura del proyecto

```
maze/
├── MazeGrid.java         # Rejilla lógica del laberinto (backtracking aleatorio)
├── MazeBuilder.java      # Traduce la rejilla a bloques reales + spawners/cofres
├── MazeBuildQueue.java   # Coloca bloques repartidos en varios ticks, por lotes
├── MazeInstance.java     # Laberinto ya construido; sabe removerse un poco solo
└── MazeLiveManager.java  # Dispara el "removerse" de cada laberinto cada 30s
```

Se ha calcado la estructura de build (`build.gradle`, `gradle.properties`,
`fabric.mod.json`, workflow de GitHub Actions) del proyecto TheRedWizard que sirvió
de referencia, para evitar el típico lío de versiones de Yarn/Loom/Fabric API
incompatibles entre sí.
