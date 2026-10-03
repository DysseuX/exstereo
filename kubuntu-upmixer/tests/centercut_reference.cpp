// Modifications Copyright (c) 2026 DysseuX (DysseuX Group)

/*******************************************************************************
*                                                                              *
* Center Cut DSP Plugin for Winamp 2/5                                         *
* Copyright (C) 2004-2007  Moitah (moitah@yahoo.com)                           *
*                                                                              *
* This program is free software; you can redistribute it and/or modify         *
* it under the terms of the GNU General Public License as published by         *
* the Free Software Foundation; either version 2 of the License, or            *
* (at your option) any later version.                                          *
*                                                                              *
* This program is distributed in the hope that it will be useful,              *
* but WITHOUT ANY WARRANTY; without even the implied warranty of               *
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the                *
* GNU General Public License for more details.                                 *
*                                                                              *
* You should have received a copy of the GNU General Public License            *
* along with this program; if not, write to the Free Software                  *
* Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA    *
*                                                                              *
*******************************************************************************/


// Standalone numerical oracle: original Moitah/Avery Lee Hartley transform.
// Adapted only for portable binary float64 I/O and CL's normalized FFT scaling.
#include <cmath>
#include <cstdio>
#include <vector>
const double twopi=6.283185307179586476925286766559;
const double invsqrt2=0.70710678118654752440084436210485;
unsigned IntegerLog2(unsigned v) {
	unsigned i = 0;

	while(v>1) {
		++i;
		v >>= 1;
	}

	return i;
}

unsigned RevBits(unsigned x, unsigned bits) {
	unsigned y = 0;

	while(bits--) {
		y = (y+y) + (x&1);
		x >>= 1;
	}

	return y;
}

void VDCreateRaisedCosineWindow(double *dst, int n, double power) {
	const double twopi_over_n = twopi / n;
	const double scalefac = 1.0 / n;

	for(int i=0; i<n; ++i) {
		dst[i] = scalefac * pow(0.5*(1.0 - cos(twopi_over_n * (i+0.5))), power);
	}
}

void VDCreateHalfSineTable(double *dst, int n) {
	const double twopi_over_n = twopi / n;

	for(int i=0; i<n; ++i) {
		dst[i] = sin(twopi_over_n * i);
	}
}

void VDCreateBitRevTable(unsigned *dst, int n) {
	unsigned bits = IntegerLog2(n);

	for(int i=0; i<n; ++i) {
		dst[i] = RevBits(i, bits);
	}
}

void CreatePostWindow(double *dst, int windowSize, int power) {
	const double powerIntegrals[8] = { 1.0, 1.0/2.0, 3.0/8.0, 5.0/16.0, 35.0/128.0,
									   63.0/256.0, 231.0/1024.0, 429.0/2048.0 };
	const double scalefac = (double)windowSize * (powerIntegrals[1] / powerIntegrals[power+1]);

	VDCreateRaisedCosineWindow(dst, windowSize, (double)power);

	for(int i=0; i<windowSize; ++i) {
		dst[i] *= scalefac;
	}
}

void VDComputeFHT(double *A, int nPoints, const double *sinTab) {
	int i, n, n2, theta_inc;

	// FHT - stage 1 and 2 (2 and 4 points)

	for(i=0; i<nPoints; i+=4) {
		const double	x0 = A[i];
		const double	x1 = A[i+1];
		const double	x2 = A[i+2];
		const double	x3 = A[i+3];

		const double	y0 = x0+x1;
		const double	y1 = x0-x1;
		const double	y2 = x2+x3;
		const double	y3 = x2-x3;

		A[i]	= y0 + y2;
		A[i+2]	= y0 - y2;

		A[i+1]	= y1 + y3;
		A[i+3]	= y1 - y3;
	}

	// FHT - stage 3 (8 points)

	for(i=0; i<nPoints; i+= 8) {
		double alpha, beta;

		alpha	= A[i+0];
		beta	= A[i+4];

		A[i+0]	= alpha + beta;
		A[i+4]	= alpha - beta;

		alpha	= A[i+2];
		beta	= A[i+6];

		A[i+2]	= alpha + beta;
		A[i+6]	= alpha - beta;

		alpha	= A[i+1];

		const double beta1 = invsqrt2*(A[i+5] + A[i+7]);
		const double beta2 = invsqrt2*(A[i+5] - A[i+7]);

		A[i+1]	= alpha + beta1;
		A[i+5]	= alpha - beta1;

		alpha	= A[i+3];

		A[i+3]	= alpha + beta2;
		A[i+7]	= alpha - beta2;
	}

	n = 16;
	n2 = 8;
	theta_inc = nPoints >> 4;

	while(n <= nPoints) {
		for(i=0; i<nPoints; i+=n) {
			int j;
			int theta = theta_inc;
			double alpha, beta;
			const int n4 = n2>>1;

			alpha	= A[i];
			beta	= A[i+n2];

			A[i]	= alpha + beta;
			A[i+n2]	= alpha - beta;

			alpha	= A[i+n4];
			beta	= A[i+n2+n4];

			A[i+n4]		= alpha + beta;
			A[i+n2+n4]	= alpha - beta;

			for(j=1; j<n4; j++) {
				double	sinval	= sinTab[theta];
				double	cosval	= sinTab[theta + (nPoints>>2)];

				double	alpha1	= A[i+j];
				double	alpha2	= A[i-j+n2];
				double	beta1	= A[i+j+n2]*cosval + A[i-j+n ]*sinval;
				double	beta2	= A[i+j+n2]*sinval - A[i-j+n ]*cosval;

				theta	+= theta_inc;

				A[i+j]		= alpha1 + beta1;
				A[i+j+n2]	= alpha1 - beta1;
				A[i-j+n2]	= alpha2 + beta2;
				A[i-j+n]	= alpha2 - beta2;
			}
		}

		n *= 2;
		n2 *= 2;
		theta_inc >>= 1;
	}
}


int main(int argc, char**argv) {
    if(argc!=3) return 2;
    constexpr int N=8192;
    FILE* in=fopen(argv[1], "rb"); if(!in) return 3;
    std::vector<double> samples(2*N),l(N),r(N),c(N),sine(N),window(N);
    std::vector<unsigned> rev(N);
    if(fread(samples.data(),sizeof(double),2*N,in)!=2*N) return 4;
    fclose(in);
    VDCreateBitRevTable(rev.data(),N); VDCreateHalfSineTable(sine.data(),N);
    VDCreateRaisedCosineWindow(window.data(),N,1.);
    for(int i=0;i<N;++i) window[i]*=N;
    for(int i=0;i<N;++i) {
        l[i]=samples[2*rev[i]]*window[rev[i]];
        r[i]=samples[2*rev[i]+1]*window[rev[i]];
    }
    VDComputeFHT(l.data(),N,sine.data()); VDComputeFHT(r.data(),N,sine.data());
    for(int i=1;i<N/2;++i) {
        double lr=.5*(l[i]+l[N-i]),li=.5*(l[i]-l[N-i]);
        double rr=.5*(r[i]+r[N-i]),ri=.5*(r[i]-r[N-i]);
        double sr=lr+rr,si=li+ri,dr=lr-rr,di=li-ri;
        double sum=sr*sr+si*si,diff=dr*dr+di*di;
        double alpha=sum>1e-15? .5-.5*sqrt(diff/sum):0.;
        double cr=sr*alpha,ci=si*alpha;
        c[rev[i]]=cr+ci; c[rev[N-i]]=cr-ci;
    }
    VDComputeFHT(c.data(),N,sine.data());
    for(int i=0;i<N;++i) c[i]*=window[i]*window[i]/(N*1.25);
    FILE*out=fopen(argv[2],"wb"); if(!out) return 5;
    bool ok=fwrite(c.data(),sizeof(double),N,out)==N;
    fclose(out); return ok?0:6;
}
