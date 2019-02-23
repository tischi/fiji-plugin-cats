
# Context Aware Trainable Segmentation (CATS)

## Citation

- Tischer, C. (2019) Fiji Plugin for Context Aware Trainable Segmentation. http://doi.org/10.5281/zenodo.2574736

## Overview

CATS is a big image data compatible [Fiji](http://fiji.sc/) plugin for trainable image segmentation. 
The code is partly based on Fiji's [Trainable Weka Segmentation](https://github.com/fiji/Trainable_Segmentation) (TWS) plugin.

Similar to Fiji's [Trainable Weka Segmentation](https://github.com/fiji/Trainable_Segmentation) and the stand-alone software [ilastik](https://www.ilastik.org/), CATS learns an image segmentation from user drawn annotations by computing image features and feeding them into a [Random Forest](https://en.wikipedia.org/wiki/Random_forest) classifier. Again similar to TWS and ilastik, the image features of CATS are based on the Eigenvalues of the [Hessian matrix](https://en.wikipedia.org/wiki/Hessian_matrix) and the [Structure Tensor](https://en.wikipedia.org/wiki/Structure_tensor), which provide rotationally invariant information about edges and ridges in an image's gray-value distribution. 

In order to improve context learning, CATS however also computes "Deep Eigenvalue Features", such as, e.g.:

```
HS( Bin3( SM( Bin3( HL( image ) ) ) ) )
```
, with abbreviations

``` 
HS = Largest Eigenvalue of Hessian Matrix
HS = Smallest Eigenvalue of Hessian Matrix
SM = Middle Eigenvalue of Structure Tensor
Bin3 = 3x3x3 Binning 
```
, where the alteration between image feature computation and image binning is inspired by the architecture of deep convolutional neural networks (DCNN) as, e.g., used in the popular [3D U-Net](https://arxiv.org/abs/1606.06650).

Similar to ilastik, but in contrast to the TWS PlugIn, CATS internally uses a tiling strategy for image segmentation and thus can be applied to arbitrarily large image data sets.

Furthermore, CATS supports:

- 2D and 3D images 
- Time-lapse image data
- Multi-channel image data
- Anisotropic image calibration
- Cluster computing (Slurm)
- Label review

## Usage Examples

- Electron microscopy volume data (e.g., 220 GB)
- Light sheet fluorescence microscopy time-lapse data

## Installation

CATS runs as a PlugIn within Fiji.

- Please install [Fiji](fiji.sc)
- Within Fiji, please enable the following [Update Sites](https://imagej.net/Update_Sites): 
    - [X] EMBL-CBA
    - [X] ImageScience

## Starting CATS

CATS can be found within Fiji's menu tree:

- [Fiji > Plugins > Segmentation > CATS]

## Using CATS

### Input Image Data

As it is common in Fiji, CATS operates on the currently active image window. Thus, before CATS can be started, one must open an image. As CATS supports multi-channel 2D and 3D time-lapse data any image can be used as input. For analyzing big image data (e.g., data that exceeds the available RAM) ImageJ's VirtualStack functionality should be used; see, e.g., [VirtualStackOpener](https://imagej.nih.gov/ij/plugins/virtual-opener.html) or [BigDataProcessor](https://github.com/tischi/fiji-plugin-bigdataprocessor#big-data-converter) on how to open VirtualStacks.

### Input Image Calibration

Upon start-up CATS asks the user to confirm the image calibration. It is very important that this information is fully correct.

Specifically, please pay attention:

- Are the number of z-planes and frames correct?
    - Sometimes z and t are mixed up...
- Are the voxel sizes in x,y, and z correct?
    - CATS is able to compute anisotropic image features!

### Results Image Setup
  
Next, CATS is providing the user with the opportunity to allocate the results image (containing the pixel probability maps) either in RAM or on disk. We generally recommend using the disk, because (i) there is hardly any performance loss, (ii) results are always saved, and (iii) data larger than RAM can be handled. The data format for storing the temporary pixel probabilities are single plane Tiff files; once the segmentation is finished there are multiple options for export (s.b.). 

##### Tip
  
The following folder structure proved to work well:
  
- `my-segmentation-project`
    - `input`
        - put your input image data here
    - `probabilities`
  		- containing the pixel probability maps generated by CATS, i.e. many Tiff files
    - `training`
        - this folder contains the training data, i.e. ARFF text files 


### Setting up Pixel Classes

First, one needs to decide how many different pixels class to segment.
  
- [Add class]
    - Adds a new class.
- [Change class names]
  	- Change the class names.
  	
### 
  
  ### Logging
  
  Information about what is happening is printed into IJ's log window.
  In addition, when you chose to save your classification results to disk (see above), another folder with the ending "--log" will be automatically created next to your results folder. The content of the logging window will be constantly written into a file in this folder.
  
  #### Put labels
  
  - in Fiji select the "Freehand line tool" 
  	- adapt line width to your sample: 'double click'
  
  - for each class you have to minimally out one label
  	- draw a line on you image
  	- add label to class by clicking on the class names or by the keyboard shortcuts [1],[2],...
  
  ##### Tips
  
  - don't draw the lables to long, because it will take a lot of memory and time to compute the features for this label
  
  #### Train classifier
  
  - [Train classifier]
  
  #### Apply classifier
  
  - Make sure the classifier is up to date, if in doubt again: [Train classifier]
  - Using Fiji's rectangle ROI select a x-y region to be classified
  - [Apply classifier]
  - This will select a minimal z-range
  - You can specify a larger z-region by typing into the "Range" field, e.g.
  	- '300,500' will classify all z-slices between these numbers
  
  #### Save labels


### Reload an existing project

- Open your image
- Start the Trainable Deep Segmentation
- [Load labels]
- [Create result image]
	- If your result image was disk-resident, selecting the existing folder will reload your previous results


### Keyboard shortcuts

- Arrow key up and down: zoom in and out

 
### Tips and tricks

#### How to put your training labels 

As this tool is able to learn long range context you have to really tell it what you want. I recommend always putting a background label just next to the actual label.


### Settings

Very often you could just leave them as is.

- Minimum tile size:
	- auto: currently the only supported setting; hopefully doing a good job.


### Open data in Imaris 9.0.1

1. Imaris: [File > Open]: load your raw data.
	- Please make sure that there is no other data than the raw data in the same folder, as this will tend to confuse Imaris!

2. Generate a tif stack containing the classification result
	- Fiji-TDWS: [Get result]
	- Fiji: Click on the result image and [Image > Duplicate]
		- This will load the result image into RAM
	- Fiji5: Click on the duplicated result image and [File > Save]

3. Imaris: [Edit > Add channels]: load classification result tif stack
	- Please make sure that there is no other data than the classification data in the same folder, as this will tend to confuse Imaris!


4. Imaris: For each class do the following:
	- [Add new Surfaces]
	- Source channel: channel 2
	- [ ] Smooth
		- It is critical that you do not smooth!!
	- Thresholding: Absolute intensity
	- Select your class by intensity gating, e.g.
		- class1: 25..39
		- class2: 45..59
		- classN: N*20+5..N*20+19
		- Note: In principle the classes start at N*20, but the classification was not very certain there..
 
NOTES:
- In fact the data do not need to be saved as Tiff, anything that Imaris can open will work.


### Open data in Imaris 9.0.0

The aim is to have a folder with files named like below:

- folder-for-imaris
	- c0--C00--T00000.tif
		- this is your raw data
	- class1--C00--T00000.tif
		- this is the classification result for class1
	- class2--C00--T00000.tif
		- ...
	- ...

1. Create a folder, e.g. cell-for-imaris
2. Copy your raw data into this folder and rename it to class0--C00--T00000.tif
3. Use the DataStreaming tools to generate one tif file per class (see below)
4. Open whole folder in Imaris

Note: You do not need to save the background class

#### Using the BigDataProcessor to generate one Tiff file per class

- Open classification results folder in BigDataProcessor
	- `Streaming`
		- `File naming scheme`: select the one with "classified..."
			- adapt the amount of z-slices to match you raw data: you may have to look this up
	- `Saving`
		- [X] Gate
			- Select the gray values that belong to one (or more) class, e.g.
				- class 2: Min = 20, Max = 39
				- class 2 and 3 together: Min = 20, Max = 59
		- [Save as stacks]

#### Open your files in Imaris

...

Tips and tricks:

- If you are working in a file server enviroment, it might be faster to first save locally, and then later copy everything in one go from your local computer to the server
 
## Additional comments

### Tile size

The tile size determines tThe minimal volume that will be classified, kind of the classification 'chunk-size'

- Considerations:
	- The larger you go the more you risk running out of memory
	- Smaller sizes will give you quicker feedback for classifying really small regions during the training
	- Larger sizes will speed up the classification of a really large volume, such as you whole data set. The reason is that the boundary voxels of each tile cannot be used for classification; as the size of the boundary region is fixed (given by the maximal downsampling), the fraction of boundary voxels compared to the full tile volume decreases with the tile size.






