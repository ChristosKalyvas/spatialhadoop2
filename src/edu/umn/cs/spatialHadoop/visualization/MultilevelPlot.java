/*******************************************************************
 * Copyright (C) 2014 by Regents of the University of Minnesota.   *
 *                                                                 *
 * This Software is released under the Apache License, Version 2.0 *
 * http://www.apache.org/licenses/LICENSE-2.0                      *
 *******************************************************************/

package edu.umn.cs.spatialHadoop.visualization;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import javax.imageio.ImageIO;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapred.ClusterStatus;
import org.apache.hadoop.mapred.FileOutputCommitter;
import org.apache.hadoop.mapred.JobClient;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.JobContext;
import org.apache.hadoop.mapred.LocalJobRunner;
import org.apache.hadoop.mapred.MapReduceBase;
import org.apache.hadoop.mapred.Mapper;
import org.apache.hadoop.mapred.OutputCollector;
import org.apache.hadoop.mapred.Reducer;
import org.apache.hadoop.mapred.Reporter;
import org.apache.hadoop.mapred.RunningJob;
import org.apache.hadoop.util.LineReader;

import edu.umn.cs.spatialHadoop.OperationsParams;
import edu.umn.cs.spatialHadoop.core.GridInfo;
import edu.umn.cs.spatialHadoop.core.Rectangle;
import edu.umn.cs.spatialHadoop.core.Shape;
import edu.umn.cs.spatialHadoop.mapred.ShapeIterInputFormat;
import edu.umn.cs.spatialHadoop.operations.FileMBR;

/**
 * Generates a multilevel image
 * @author Ahmed Eldawy
 *
 */
public class MultilevelPlot {
  private static final Log LOG = LogFactory.getLog(MultilevelPlot.class);
  /**Configuration entry for input MBR*/
  private static final String InputMBR = "mbr";

  /**Maximum height for a pyramid to be generated by one machine*/
  private static final String MaxLevelsPerReducer = "MultilevelPlot.MaxLevelsPerMachine";

  /**The maximum level on which flat partitioning can be used*/
  private static final String FlatPartitioningLevelThreshold = "MultilevelPlot.FlatPartitioningLevelThreshold";
  
  public static class FlatPartition extends MapReduceBase 
    implements Mapper<Rectangle, Iterable<? extends Shape>, TileIndex, RasterLayer>,
    Reducer<TileIndex, RasterLayer, TileIndex, RasterLayer> {
    
    /**Minimum and maximum levels of the pyramid to plot (inclusive and zero-based)*/
    private int minLevel, maxLevel;
    
    /**The grid at the bottom level (i.e., maxLevel)*/
    private GridInfo bottomGrid;

    /**The MBR of the input area to draw*/
    private Rectangle inputMBR;

    /**The rasterizer associated with this job*/
    private Rasterizer rasterizer;

    /**Fixed width for one tile*/
    private int tileWidth;

    /**Fixed height for one tile */
    private int tileHeight;
    
    /**Buffer size that should be taken in the maximum level*/
    private double bufferSizeXMaxLevel;

    private double bufferSizeYMaxLevel;

    private boolean writeOutput;
    
    /**Whether the configured rasterize supports smooth or not*/
    private boolean smooth;
    
    @Override
    public void configure(JobConf job) {
      super.configure(job);
      String[] strLevels = job.get("levels", "7").split("\\.\\.");
      if (strLevels.length == 1) {
        minLevel = 0;
        maxLevel = Integer.parseInt(strLevels[0]);
      } else {
        minLevel = Integer.parseInt(strLevels[0]);
        maxLevel = Integer.parseInt(strLevels[1]);
      }
      this.inputMBR = (Rectangle) OperationsParams.getShape(job, InputMBR);
      this.bottomGrid = new GridInfo(inputMBR.x1, inputMBR.y1, inputMBR.x2, inputMBR.y2);
      this.bottomGrid.rows = bottomGrid.columns = 1 << maxLevel;
      this.tileWidth = job.getInt("tilewidth", 256);
      this.tileHeight = job.getInt("tileheight", 256);
      this.rasterizer = Rasterizer.getRasterizer(job);
      this.smooth = rasterizer.isSmooth();
      int radius = rasterizer.getRadius();
      this.bufferSizeXMaxLevel = radius * inputMBR.getWidth() / (tileWidth * (1 << maxLevel));
      this.bufferSizeYMaxLevel = radius * inputMBR.getHeight() / (tileHeight * (1 << maxLevel));
      // Whether to write the final image or not
      this.writeOutput = job.getBoolean("output", true);
    }
    
    @Override
    public void map(Rectangle partitionMBR, Iterable<? extends Shape> shapes,
        OutputCollector<TileIndex, RasterLayer> output, Reporter reporter)
        throws IOException {
      if (smooth)
        shapes = rasterizer.smooth(shapes);
      TileIndex key = new TileIndex();
      Map<TileIndex, RasterLayer> rasterLayers = new HashMap<TileIndex, RasterLayer>();
      for (Shape shape : shapes) {
        Rectangle shapeMBR = shape.getMBR();
        if (shapeMBR == null)
          continue;
        java.awt.Rectangle overlappingCells =
            bottomGrid.getOverlappingCells(shapeMBR.buffer(bufferSizeXMaxLevel, bufferSizeYMaxLevel));
        // Iterate over levels from bottom up
        for (key.level = maxLevel; key.level >= minLevel; key.level--) {
          for (key.x = overlappingCells.x; key.x < overlappingCells.x + overlappingCells.width; key.x++) {
            for (key.y = overlappingCells.y; key.y < overlappingCells.y + overlappingCells.height; key.y++) {
              RasterLayer rasterLayer = rasterLayers.get(key);
              if (rasterLayer == null) {
                Rectangle tileMBR = new Rectangle();
                int gridSize = 1 << key.level;
                tileMBR.x1 = (inputMBR.x1 * (gridSize - key.x) + inputMBR.x2 * key.x) / gridSize;
                tileMBR.x2 = (inputMBR.x1 * (gridSize - (key.x + 1)) + inputMBR.x2 * (key.x+1)) / gridSize;
                tileMBR.y1 = (inputMBR.y1 * (gridSize - key.y) + inputMBR.y2 * key.y) / gridSize;
                tileMBR.y2 = (inputMBR.y1 * (gridSize - (key.y + 1)) + inputMBR.y2 * (key.y+1)) / gridSize;
                rasterLayer = rasterizer.createRaster(tileWidth, tileHeight, tileMBR);
                rasterLayers.put(key.clone(), rasterLayer);
              }
              rasterizer.rasterize(rasterLayer, shape);
            }
          }
          // Update overlappingCells for the higher level
          int updatedX1 = overlappingCells.x / 2;
          int updatedY1 = overlappingCells.y / 2;
          int updatedX2 = (overlappingCells.x + overlappingCells.width - 1) / 2;
          int updatedY2 = (overlappingCells.y + overlappingCells.height - 1) / 2;
          overlappingCells.x = updatedX1;
          overlappingCells.y = updatedY1;
          overlappingCells.width = updatedX2 - updatedX1 + 1;
          overlappingCells.height = updatedY2 - updatedY1 + 1;
        }
      }
      // Write all created layers to the output
      for (Map.Entry<TileIndex, RasterLayer> entry : rasterLayers.entrySet()) {
        output.collect(entry.getKey(), entry.getValue());
      }
    }
    
    @Override
    public void reduce(TileIndex tileID, Iterator<RasterLayer> intermediateLayers,
        OutputCollector<TileIndex, RasterLayer> output, Reporter reporter)
        throws IOException {
      Rectangle tileMBR = new Rectangle();
      int gridSize = 1 << tileID.level;
      tileMBR.x1 = (inputMBR.x1 * (gridSize - tileID.x) + inputMBR.x2 * tileID.x) / gridSize;
      tileMBR.x2 = (inputMBR.x1 * (gridSize - (tileID.x + 1)) + inputMBR.x2 * (tileID.x+1)) / gridSize;
      tileMBR.y1 = (inputMBR.y1 * (gridSize - tileID.y) + inputMBR.y2 * tileID.y) / gridSize;
      tileMBR.y2 = (inputMBR.y1 * (gridSize - (tileID.y + 1)) + inputMBR.y2 * (tileID.y+1)) / gridSize;

      RasterLayer finalLayer = rasterizer.createRaster(tileWidth, tileHeight, tileMBR);
      while (intermediateLayers.hasNext()) {
        rasterizer.merge(finalLayer, intermediateLayers.next());
      }
      
      if (writeOutput)
        output.collect(tileID, finalLayer);
    }
  }

  /**
   * Finalizes the job by adding a default empty tile and writing
   * an HTML file to navigate generated images
   * folder.
   * @author Ahmed Eldawy
   *
   */
  public static class MultiLevelOutputCommitter extends FileOutputCommitter {
    @Override
    public void commitJob(JobContext context) throws IOException {
      super.commitJob(context);

      JobConf job = context.getJobConf();
      Path outPath = PyramidOutputFormat2.getOutputPath(job);
      FileSystem outFs = outPath.getFileSystem(job);

      System.out.println("Writing default empty image");
      // Write a default empty image to be displayed for non-generated tiles
      int tileWidth = job.getInt("tilewidth", 256);
      int tileHeight = job.getInt("tileheight", 256);
      BufferedImage emptyImg = new BufferedImage(tileWidth, tileHeight,
          BufferedImage.TYPE_INT_ARGB);
      Graphics2D g = new SimpleGraphics(emptyImg);
      g.setBackground(new Color(0, 0, 0, 0));
      g.clearRect(0, 0, tileWidth, tileHeight);
      g.dispose();

      OutputStream out = outFs.create(new Path(outPath, "default.png"));
      ImageIO.write(emptyImg, "png", out);
      out.close();

      // Get the correct levels.
      String[] strLevels = job.get("levels", "7").split("\\.\\.");
      int minLevel, maxLevel;
      if (strLevels.length == 1) {
        minLevel = 0;
        maxLevel = Integer.parseInt(strLevels[0]);
      } else {
        minLevel = Integer.parseInt(strLevels[0]);
        maxLevel = Integer.parseInt(strLevels[1]);
      }

      // Add an HTML file that visualizes the result using Google Maps
      System.out.println("Writing the HTML viewer file");
      LineReader templateFileReader = new LineReader(getClass()
          .getResourceAsStream("/zoom_view.html"));
      PrintStream htmlOut = new PrintStream(outFs.create(new Path(outPath,
          "index.html")));
      Text line = new Text();
      while (templateFileReader.readLine(line) > 0) {
        String lineStr = line.toString();
        lineStr = lineStr.replace("#{TILE_WIDTH}", Integer.toString(tileWidth));
        lineStr = lineStr.replace("#{TILE_HEIGHT}",
            Integer.toString(tileHeight));
        lineStr = lineStr.replace("#{MAX_ZOOM}", Integer.toString(maxLevel));
        lineStr = lineStr.replace("#{MIN_ZOOM}", Integer.toString(minLevel));
        lineStr = lineStr.replace("#{TILE_URL}", "'tile_' + zoom + '_' + coord.x + '-' + coord.y + '.png'");

        htmlOut.println(lineStr);
      }
      templateFileReader.close();
      htmlOut.close();
    }
  }
  
  /**
   * A MapReduce visualization program that uses pyramid-based partitioning.
   * The map function partitions the data using coarse-grained partitioning,
   * while the reduce function generates a set of pyramid tiles for each partition.
   * @author Ahmed Eldawy
   *
   */
  public static class PyramidPartition extends MapReduceBase 
    implements Mapper<Rectangle, Iterable<? extends Shape>, TileIndex, Shape>,
    Reducer<TileIndex, Shape, TileIndex, RasterLayer> {

    private int minLevel, maxLevel;
    /**Maximum level to replicate to*/
    private int maxLevelToReplicate;
    private Rectangle inputMBR;
    /**The grid of the lowest (deepest) level of the pyramid*/
    private GridInfo bottomGrid;
    /**The user-configured rasterizer*/
    private Rasterizer rasterizer;
    /**The radius of effect of each record in input coordinates*/
    private double bufferSizeXMaxLevel, bufferSizeYMaxLevel;
    /**Maximum levels to generate per reducer*/
    private int maxLevelsPerReducer;
    /**Size of each tile in pixels*/
    private int tileWidth, tileHeight;
    /**Radius of effect of each shape*/
    private int radius;
    private boolean writeOutput;
    /**Whether the configured rasterizer defines a smooth function or not*/
    private boolean smooth;

    @Override
    public void configure(JobConf job) {
      super.configure(job);
      String[] strLevels = job.get("levels", "7").split("\\.\\.");
      if (strLevels.length == 1) {
        minLevel = 0;
        maxLevel = Integer.parseInt(strLevels[0]);
      } else {
        minLevel = Integer.parseInt(strLevels[0]);
        maxLevel = Integer.parseInt(strLevels[1]);
      }
      this.maxLevelsPerReducer = job.getInt(MaxLevelsPerReducer, 3);
      // Adjust maxLevelToReplicate so that the difference is multiple of maxLevelsPerMachine
      this.maxLevelToReplicate = maxLevel - (maxLevel - minLevel) % maxLevelsPerReducer;
      this.inputMBR = (Rectangle) OperationsParams.getShape(job, InputMBR);
      this.bottomGrid = new GridInfo(inputMBR.x1, inputMBR.y1, inputMBR.x2, inputMBR.y2);
      this.bottomGrid.rows = bottomGrid.columns = (1 << maxLevelToReplicate); // 2 ^ maxLevel
      int tileWidth = job.getInt("tilewidth", 256);
      int tileHeight = job.getInt("tileheight", 256);
      this.rasterizer = Rasterizer.getRasterizer(job);
      this.smooth = rasterizer.isSmooth();
      this.radius = rasterizer.getRadius();
      this.bufferSizeXMaxLevel = radius * inputMBR.getWidth() / (tileWidth * (1 << maxLevelToReplicate));
      this.bufferSizeYMaxLevel = radius * inputMBR.getHeight() / (tileHeight * (1 << maxLevelToReplicate));
      this.tileWidth = job.getInt("tilewidth", 256);
      this.tileHeight = job.getInt("tileheight", 256);
      // Whether to write the final image or not
      this.writeOutput = job.getBoolean("output", true);
    }
    
    @Override
    public void map(Rectangle inputMBR, Iterable<? extends Shape> shapes,
        OutputCollector<TileIndex, Shape> output, Reporter reporter)
        throws IOException {
      TileIndex key = new TileIndex();
      for (Shape shape : shapes) {
        Rectangle shapeMBR = shape.getMBR();
        if (shapeMBR == null)
          continue;
        java.awt.Rectangle overlappingCells =
            bottomGrid.getOverlappingCells(shapeMBR.buffer(bufferSizeXMaxLevel, bufferSizeYMaxLevel));
        // Iterate over levels from bottom up
        for (key.level = maxLevelToReplicate; key.level >= minLevel; key.level -= maxLevelsPerReducer) {
          for (key.x = overlappingCells.x; key.x < overlappingCells.x + overlappingCells.width; key.x++) {
            for (key.y = overlappingCells.y; key.y < overlappingCells.y + overlappingCells.height; key.y++) {
              output.collect(key, shape);
            }
          }
          // Shrink overlapping cells to match the upper level
          int updatedX1 = overlappingCells.x >> maxLevelsPerReducer;
          int updatedY1 = overlappingCells.y >> maxLevelsPerReducer;
          int updatedX2 = (overlappingCells.x + overlappingCells.width - 1) >> maxLevelsPerReducer;
          int updatedY2 = (overlappingCells.y + overlappingCells.height - 1) >> maxLevelsPerReducer;
          overlappingCells.x = updatedX1;
          overlappingCells.y = updatedY1;
          overlappingCells.width = updatedX2 - updatedX1 + 1;
          overlappingCells.height = updatedY2 - updatedY1 + 1;
        }
      }
    }
    
    @Override
    public void reduce(TileIndex tileID, Iterator<Shape> shapes,
        OutputCollector<TileIndex, RasterLayer> output, Reporter reporter)
        throws IOException {
      // Find first and last levels to generate in this reducer
      int level1 = Math.max(tileID.level, minLevel);
      int level2 = Math.min(tileID.level + maxLevelsPerReducer - 1, maxLevel);

      // Portion of the bottom grid that falls under the given tile
      int tileOffsetX = tileID.x << (level2 - tileID.level);
      int tileOffsetY = tileID.y << (level2 - tileID.level);
      GridInfo bottomGrid = new GridInfo();
      int gridSize = 1 << tileID.level;
      bottomGrid.x1 = (inputMBR.x1 * (gridSize - tileID.x) + inputMBR.x2 * tileID.x) / gridSize;
      bottomGrid.x2 = (inputMBR.x1 * (gridSize - (tileID.x + 1)) + inputMBR.x2 * (tileID.x+1)) / gridSize;
      bottomGrid.y1 = (inputMBR.y1 * (gridSize - tileID.y) + inputMBR.y2 * tileID.y) / gridSize;
      bottomGrid.y2 = (inputMBR.y1 * (gridSize - (tileID.y + 1)) + inputMBR.y2 * (tileID.y+1)) / gridSize;
      bottomGrid.columns = bottomGrid.rows = (1 << (level2 - level1));
      double bufferSizeXLevel2 = radius * inputMBR.getWidth() / (tileWidth * (1 << level2));
      double bufferSizeYLevel2 = radius * inputMBR.getHeight() / (tileHeight * (1 << level2));
      Map<TileIndex, RasterLayer> rasterLayers = new HashMap<TileIndex, RasterLayer>();
      
      TileIndex key = new TileIndex();
      
      LOG.info("Rasterizing");
      int count = 0;
      if (smooth) {
        final Iterator<Shape> inputShapes = shapes;
        shapes = rasterizer.smooth(new Iterable<Shape>() {
          @Override
          public Iterator<Shape> iterator() {
            return inputShapes;
          }
        }).iterator();
      }
      while (shapes.hasNext()) {
        count++;
        Shape shape = shapes.next();
        Rectangle shapeMBR = shape.getMBR();
        if (shapeMBR == null)
          continue;
        java.awt.Rectangle overlappingCells =
            bottomGrid.getOverlappingCells(shapeMBR.buffer(bufferSizeXLevel2, bufferSizeYLevel2));
        // Shift overlapping cells to be in the full pyramid rather than
        // the sub-pyramid rooted at tileID
        overlappingCells.x += tileOffsetX;
        overlappingCells.y += tileOffsetY;
        // Iterate over levels from bottom up
        for (key.level = level2; key.level >= level1; key.level--) {
          for (key.x = overlappingCells.x; key.x < overlappingCells.x + overlappingCells.width; key.x++) {
            for (key.y = overlappingCells.y; key.y < overlappingCells.y + overlappingCells.height; key.y++) {
              RasterLayer rasterLayer = rasterLayers.get(key);
              if (rasterLayer == null) {
                Rectangle tileMBR = new Rectangle();
                gridSize = 1 << key.level;
                tileMBR.x1 = (inputMBR.x1 * (gridSize - key.x) + inputMBR.x2 * key.x) / gridSize;
                tileMBR.x2 = (inputMBR.x1 * (gridSize - (key.x + 1)) + inputMBR.x2 * (key.x+1)) / gridSize;
                tileMBR.y1 = (inputMBR.y1 * (gridSize - key.y) + inputMBR.y2 * key.y) / gridSize;
                tileMBR.y2 = (inputMBR.y1 * (gridSize - (key.y + 1)) + inputMBR.y2 * (key.y+1)) / gridSize;
                rasterLayer = rasterizer.createRaster(tileWidth, tileHeight, tileMBR);
                rasterLayers.put(key.clone(), rasterLayer);
              }
              rasterizer.rasterize(rasterLayer, shape);
            }
          }
          
          // Update overlappingCells for the higher level
          int updatedX1 = overlappingCells.x / 2;
          int updatedY1 = overlappingCells.y / 2;
          int updatedX2 = (overlappingCells.x + overlappingCells.width - 1) / 2;
          int updatedY2 = (overlappingCells.y + overlappingCells.height - 1) / 2;
          overlappingCells.x = updatedX1;
          overlappingCells.y = updatedY1;
          overlappingCells.width = updatedX2 - updatedX1 + 1;
          overlappingCells.height = updatedY2 - updatedY1 + 1;
        }
      }
      LOG.info("Rasterized "+count+" records");
      // Write all created layers to the output as images
      for (Map.Entry<TileIndex, RasterLayer> entry : rasterLayers.entrySet()) {
        if (writeOutput)
          output.collect(entry.getKey(), entry.getValue());
      }
      LOG.info("Wrote "+rasterLayers.size()+ " images to output");
    }

  }

  public static RunningJob plotMapReduce(Path[] inFiles, Path outFile,
      Class<? extends Rasterizer> rasterizerClass, OperationsParams params) throws IOException {
    Rasterizer rasterizer;
    try {
      rasterizer = rasterizerClass.newInstance();
    } catch (InstantiationException e) {
      throw new RuntimeException("Error creating rastierizer", e);
    } catch (IllegalAccessException e) {
      throw new RuntimeException("Error creating rastierizer", e);
    }
    
    JobConf job = new JobConf(params, SingleLevelPlot.class);
    job.setJobName("MultiLevelPlot");
    // Set rasterizer
    Rasterizer.setRasterizer(job, rasterizerClass);
    // Set input file MBR
    Rectangle inputMBR = (Rectangle) params.getShape("rect");
    if (inputMBR == null)
      inputMBR = FileMBR.fileMBR(inFiles, params);
    
    // Adjust width and height if aspect ratio is to be kept
    if (params.is("keepratio", true)) {
      // Expand input file to a rectangle for compatibility with the pyramid
      // structure
      if (inputMBR.getWidth() > inputMBR.getHeight()) {
        inputMBR.y1 -= (inputMBR.getWidth() - inputMBR.getHeight()) / 2;
        inputMBR.y2 = inputMBR.y1 + inputMBR.getWidth();
      } else {
        inputMBR.x1 -= (inputMBR.getHeight() - inputMBR.getWidth() / 2);
        inputMBR.x2 = inputMBR.x1 + inputMBR.getHeight();
      }
    }
    OperationsParams.setShape(job, InputMBR, inputMBR);
    
    // Set input and output
    job.setInputFormat(ShapeIterInputFormat.class);
    ShapeIterInputFormat.setInputPaths(job, inFiles);
    job.setOutputFormat(PyramidOutputFormat2.class);
    PyramidOutputFormat2.setOutputPath(job, outFile);
    
    // Set mapper, reducer and committer
    String partitionTechnique = params.get("partition", "flat");
    if (partitionTechnique.equalsIgnoreCase("flat")) {
      // Use flat partitioning
      job.setMapperClass(FlatPartition.class);
      job.setMapOutputKeyClass(TileIndex.class);
      job.setMapOutputValueClass(rasterizer.getRasterClass());
      job.setReducerClass(FlatPartition.class);
    } else if (partitionTechnique.equalsIgnoreCase("pyramid")) {
      // Use pyramid partitioning
      Shape shape = params.getShape("shape");
      job.setMapperClass(PyramidPartition.class);
      job.setMapOutputKeyClass(TileIndex.class);
      job.setMapOutputValueClass(shape.getClass());
      job.setReducerClass(PyramidPartition.class);
    } else {
      throw new RuntimeException("Unknown partitioning technique '"+partitionTechnique+"'");
    }
    job.setOutputCommitter(MultiLevelOutputCommitter.class);
    
    // Set number of mappers and reducers
    ClusterStatus clusterStatus = new JobClient(job).getClusterStatus();
    job.setNumMapTasks(5 * Math.max(1, clusterStatus.getMaxMapTasks()));
    job.setNumReduceTasks(Math.max(1, clusterStatus.getMaxReduceTasks()));

    // Use multithreading in case the job is running locally
    job.setInt(LocalJobRunner.LOCAL_MAX_MAPS, Runtime.getRuntime().availableProcessors());

    // Start the job
    if (params.getBoolean("background", false)) {
      // Run in background
      JobClient jc = new JobClient(job);
      return jc.submitJob(job);
    } else {
      // Run and block until it is finished
      return JobClient.runJob(job);
    }
  }
  
  public static RunningJob plot(Path[] inPaths, Path outPath,
      Class<? extends Rasterizer> rasterizerClass, OperationsParams params) throws IOException {
    // Decide how to run it based on range of levels to generate
    String[] strLevels = params.get("levels", "7").split("\\.\\.");
    int minLevel, maxLevel;
    if (strLevels.length == 1) {
      minLevel = 0;
      maxLevel = Integer.parseInt(strLevels[0]) - 1;
    } else {
      minLevel = Integer.parseInt(strLevels[0]);
      maxLevel = Integer.parseInt(strLevels[1]);
    }
    // Create an output directory that will hold the output of the two jobs
    FileSystem outFS = outPath.getFileSystem(params);
    outFS.create(outPath);
    
    int maxLevelWithFlatPartitioning = params.getInt(FlatPartitioningLevelThreshold, 4);
    RunningJob runningJob = null;
    if (minLevel <= maxLevelWithFlatPartitioning) {
      OperationsParams flatPartitioning = new OperationsParams(params);
      flatPartitioning.set("levels", minLevel+".."+Math.min(maxLevelWithFlatPartitioning, maxLevel));
      flatPartitioning.set("partition", "flat");
      LOG.info("Using flat partitioning in levels "+flatPartitioning.get("levels"));
      runningJob = plotMapReduce(inPaths, new Path(outPath, "flat"), rasterizerClass, flatPartitioning);
    }
    if (maxLevel > maxLevelWithFlatPartitioning) {
      OperationsParams pyramidPartitioning = new OperationsParams(params);
      pyramidPartitioning.set("levels", Math.max(minLevel, maxLevelWithFlatPartitioning+1)+".."+maxLevel);
      pyramidPartitioning.set("partition", "pyramid");
      LOG.info("Using pyramid partitioning in levels "+pyramidPartitioning.get("levels"));
      runningJob = plotMapReduce(inPaths, new Path(outPath, "pyramid"), rasterizerClass, pyramidPartitioning);
    }
    // Write a new HTML file that displays both parts of the pyramid
    // Add an HTML file that visualizes the result using Google Maps
    LineReader templateFileReader = new LineReader(MultilevelPlot.class
        .getResourceAsStream("/zoom_view.html"));
    PrintStream htmlOut = new PrintStream(outFS.create(new Path(outPath,
        "index.html")));
    Text line = new Text();
    while (templateFileReader.readLine(line) > 0) {
      String lineStr = line.toString();
      lineStr = lineStr.replace("#{TILE_WIDTH}", Integer.toString(params.getInt("tilewidth", 256)));
      lineStr = lineStr.replace("#{TILE_HEIGHT}", Integer.toString(params.getInt("tileheight", 256)));
      lineStr = lineStr.replace("#{MAX_ZOOM}", Integer.toString(maxLevel));
      lineStr = lineStr.replace("#{MIN_ZOOM}", Integer.toString(minLevel));
      lineStr = lineStr.replace("#{TILE_URL}", "(zoom <= "+maxLevelWithFlatPartitioning+"? 'flat' : 'pyramid')+('/tile_' + zoom + '_' + coord.x + '-' + coord.y + '.png')");

      htmlOut.println(lineStr);
    }
    templateFileReader.close();
    htmlOut.close();
    
    return runningJob;
  }
}
